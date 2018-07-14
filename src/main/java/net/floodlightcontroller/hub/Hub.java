/**
*    Copyright 2011, Big Switch Networks, Inc. 
*    Originally created by David Erickson, Stanford University
* 
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
**/

package net.floodlightcontroller.hub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.IPv6;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.L3RoutingManager;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.routing.VirtualGatewayInstance;
import net.floodlightcontroller.routing.VirtualGatewayInterface;
import net.floodlightcontroller.util.OFMessageUtils;

import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U16;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu) - 04/04/10
 */
public class Hub extends ForwardingBase implements IFloodlightModule, IOFMessageListener {
	private static final short DECISION_BITS = 24;
    private static final short DECISION_SHIFT = 0;
    private static final long DECISION_MASK = ((1L << DECISION_BITS) - 1) << DECISION_SHIFT;

    private static final short FLOWSET_BITS = 28;
    protected static final short FLOWSET_SHIFT = DECISION_BITS;
    private static final long FLOWSET_MASK = ((1L << FLOWSET_BITS) - 1) << FLOWSET_SHIFT;
    private static final long FLOWSET_MAX = (long) (Math.pow(2, FLOWSET_BITS) - 1);
	private enum HubType {USE_PACKET_OUT, USE_FLOW_MOD};
	private static L3RoutingManager l3manager;
	protected static Logger logger;
    private IFloodlightProviderService floodlightProvider;
    protected static FlowSetIdRegistry flowSetIdRegistry;
    
    protected static class FlowSetIdRegistry {
        private volatile Map<NodePortTuple, Set<U64>> nptToFlowSetIds;
        private volatile Map<U64, Set<NodePortTuple>> flowSetIdToNpts;
        
        private volatile long flowSetGenerator = -1;

        private static volatile FlowSetIdRegistry instance;

        private FlowSetIdRegistry() {
            nptToFlowSetIds = new ConcurrentHashMap<>();
            flowSetIdToNpts = new ConcurrentHashMap<>();
        }

        protected static FlowSetIdRegistry getInstance() {
            if (instance == null) {
                instance = new FlowSetIdRegistry();
            }
            return instance;
        }
        
        /**
         * Only for use by unit test to help w/ordering
         * @param seed
         */
        protected void seedFlowSetIdForUnitTest(int seed) {
            flowSetGenerator = seed;
        }
        
        protected synchronized U64 generateFlowSetId() {
            flowSetGenerator += 1;
            if (flowSetGenerator == FLOWSET_MAX) {
                flowSetGenerator = 0;
                log.warn("Flowset IDs have exceeded capacity of {}. Flowset ID generator resetting back to 0", FLOWSET_MAX);
            }
            U64 id = U64.of(flowSetGenerator << FLOWSET_SHIFT);
            log.debug("Generating flowset ID {}, shifted {}", flowSetGenerator, id);
            return id;
        }

        private void registerFlowSetId(NodePortTuple npt, U64 flowSetId) {
            if (nptToFlowSetIds.containsKey(npt)) {
                Set<U64> ids = nptToFlowSetIds.get(npt);
                ids.add(flowSetId);
            } else {
                Set<U64> ids = new HashSet<>();
                ids.add(flowSetId);
                nptToFlowSetIds.put(npt, ids);
            }  

            if (flowSetIdToNpts.containsKey(flowSetId)) {
                Set<NodePortTuple> npts = flowSetIdToNpts.get(flowSetId);
                npts.add(npt);
            } else {
                Set<NodePortTuple> npts = new HashSet<>();
                npts.add(npt);
                flowSetIdToNpts.put(flowSetId, npts);
            }
        }

        private Set<U64> getFlowSetIds(NodePortTuple npt) {
            return nptToFlowSetIds.get(npt);
        }

        private Set<NodePortTuple> getNodePortTuples(U64 flowSetId) {
            return flowSetIdToNpts.get(flowSetId);
        }

        private void removeNodePortTuple(NodePortTuple npt) {
            nptToFlowSetIds.remove(npt);

            Iterator<Set<NodePortTuple>> itr = flowSetIdToNpts.values().iterator();
            while (itr.hasNext()) {
                Set<NodePortTuple> npts = itr.next();
                npts.remove(npt);
            }
        }

        private void removeExpiredFlowSetId(U64 flowSetId, NodePortTuple avoid, Iterator<U64> avoidItr) {
            flowSetIdToNpts.remove(flowSetId);

            Iterator<Entry<NodePortTuple, Set<U64>>> itr = nptToFlowSetIds.entrySet().iterator();
            boolean removed = false;
            while (itr.hasNext()) {
                Entry<NodePortTuple, Set<U64>> e = itr.next();
                if (e.getKey().equals(avoid) && ! removed) {
                    avoidItr.remove();
                    removed = true;
                } else {
                    Set<U64> ids = e.getValue();
                    ids.remove(flowSetId);
                }
            }
        }
    }

    /**
     * @param floodlightProvider the floodlightProvider to set
     */
    public void setFloodlightProvider(IFloodlightProviderService floodlightProvider) {
        this.floodlightProvider = floodlightProvider;
    }

    @Override
    public String getName() {
        return Hub.class.getPackage().getName();
    }
    
    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
    	switch (msg.getType()) {
	    	case PACKET_IN:
	            Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
	            
	            MacAddress srcMac = eth.getSourceMACAddress();
	            VlanVid vlanId = VlanVid.ofVlan(eth.getVlanID());
	            logger.info("eth: {}", eth.getEtherType().toString());
	            if (eth.getEtherType() == EthType.IPv4) {
	                /* We got an IPv4 packet; get the payload from Ethernet */
	                IPv4 ipv4 = (IPv4) eth.getPayload();
	                
	                byte[] ipOptions = ipv4.getOptions();
	                IPv4Address dstIp = ipv4.getDestinationAddress();
	                String ipSrc = ipv4.getSourceAddress().toString();
	                String ipDst = dstIp.toString();
	                if ((ipDst.compareTo("10.10.0.3") == 0) || (ipDst.compareTo("10.10.0.4") == 0) || (ipDst.compareTo("10.10.0.5") == 0)) {
	                	if(ipv4.getProtocol() == IpProtocol.TCP) {
		                    /* We got a TCP packet; get the payload from IPv4 */
		                    TCP tcp = (TCP) ipv4.getPayload();
		      
		                    /* Various getters and setters are exposed in TCP */
		                    TransportPort srcPort = tcp.getSourcePort();
		                    TransportPort dstPort = tcp.getDestinationPort();
		                    if((dstPort.toString().compareTo("5001") == 0) || (dstPort.toString().compareTo("5002") == 0)) {
//		                    	Optional<VirtualGatewayInstance> instance = getGatewayInstance(sw.getId());
		                    	VirtualGatewayInstance gateway = null;
		//	                    short flags = tcp.getFlags();
			                    OFMessage outMessage = null;
			                	OFMessage outMessage2 = null;
			                	OFMessage outMessage3;
			                	HubType ht = HubType.USE_PACKET_OUT;
			                	switch (ht) {
			            	    	case USE_FLOW_MOD:
			            	            outMessage = createHubFlowMod(sw, msg);
			            	            outMessage2 = createHubFlowMod(sw, msg);
			            	            outMessage3 = createHubFlowMod(sw, msg);
			            	            break;
			            	        default:
			            	    	case USE_PACKET_OUT:
//			            	    		logger.info("=>Sending from {} to {}",ipSrc, ipDst);
//		            	    			createHubPacketOut(sw, msg, eth, ipv4);
			            	    		if(ipDst.compareTo("10.10.0.3") == 0)
			            	    		{
			            	    			logger.info("=>Sending from {} to {} through 4 and 5",ipSrc, ipDst);
			            	    			logger.info("=>Sending from {} to {} through 1", new Object[] {ipSrc, ipDst, sw.getId()});
			            	    			createHubPacketOut(sw, msg, 4, eth, ipv4, cntx, gateway);
//			            	    			createHubPacketOut(sw, msg, 5, eth, ipv4, cntx, gateway);
			            	    		}
			            	    		else if(ipDst.compareTo("10.10.0.4") == 0)
			            	    		{
			            	    			logger.info("=>Sending from {} to {} through 3 and 5",ipSrc, ipDst);
			            	    			logger.info("=>Sending from {} to {} through 1", new Object[] {ipSrc, ipDst, sw.getId()});
			            	    			createHubPacketOut(sw, msg, 3, eth, ipv4, cntx, gateway);
//			            	    			createHubPacketOut(sw, msg, 5, eth, ipv4, cntx, gateway);
			            	    		}
			            	    		else if(ipDst.compareTo("10.10.0.5") == 0)
			            	    		{
			            	    			logger.info("=>Sending from {} to {} through {} 3 and 4",ipSrc, ipDst);
			            	    			logger.info("=>Sending from {} to {} through 1", new Object[] {ipSrc, ipDst, sw.getId()});
			            	    			createHubPacketOut(sw, msg, 3, eth, ipv4, cntx, gateway);
			            	    			createHubPacketOut(sw, msg, 4, eth, ipv4, cntx, gateway);
			            	    		}
			            	            
			            	            break;
			                	}
//			                	sw.write(outMessage);
//			                    sw.write(outMessage2);
	//		                    sw.write(outMessage3);
		                    }
                		}
	                }
	                else if ((ipSrc.compareTo("10.10.0.3") == 0) || (ipSrc.compareTo("10.10.0.4") == 0))
	                {
	                	if(ipv4.getProtocol() == IpProtocol.TCP) {
		                    /* We got a TCP packet; get the payload from IPv4 */
		                    TCP tcp = (TCP) ipv4.getPayload();
		      
		                    /* Various getters and setters are exposed in TCP */
		                    TransportPort srcPort = tcp.getSourcePort();
		                    TransportPort dstPort = tcp.getDestinationPort();
		                    if((srcPort.toString().compareTo("5001") == 0) || (srcPort.toString().compareTo("5002") == 0)) {
		                    	Optional<VirtualGatewayInstance> instance = getGatewayInstance(sw.getId());
		                    	VirtualGatewayInstance gateway = null;
		//	                    short flags = tcp.getFlags();
			                    OFMessage outMessage;
			                	OFMessage outMessage2;
			                	OFMessage outMessage3;
			                	HubType ht = HubType.USE_PACKET_OUT;
			                	switch (ht) {
			            	    	case USE_FLOW_MOD:
			            	            createHubFlowMod(sw, msg);
			            	            createHubFlowMod(sw, msg);
			            	            createHubFlowMod(sw, msg);
			            	            break;
			            	        default:
			            	    	case USE_PACKET_OUT:
//			            	    		if(ipDst.compareTo("10.10.0.1") == 0)
//			            	    		{
//			            	    			logger.info("Sending from {} to {} through 2",ipSrc, ipDst);
//			            	    			createHubPacketOut(sw, msg, 2, eth, ipv4);
//			            	    		}
//			            	    		else
//			            	    		{
			            	    			logger.info("=>Sending from {} to {} through 1", new Object[] {ipSrc, ipDst, sw.getId()});
			            	    			createHubPacketOut(sw, msg, 1,eth, ipv4, cntx, gateway);
//			            	    		}
//			            	            break;
			                	}
//			                    sw.write(outMessage);
	//		                    sw.write(outMessage2);
	//		                    sw.write(outMessage3);
		                    }
                		}
	                }
	                else
	                {
//	                	OFPacketIn pi = (OFPacketIn) msg;
//		            	doL2ForwardFlow(sw, pi, null, cntx, false);
	                }
	            }
	            else
	            {
//	            	OFPacketIn pi = (OFPacketIn) msg;
//	            	doL2ForwardFlow(sw, pi, null, cntx, false);
	            }
	            break;
	        default:
	            break;
        }
    	
        
        return Command.CONTINUE;
    }
    
    private OFMessage createHubFlowMod(IOFSwitch sw, OFMessage msg) {
    	OFPacketIn pi = (OFPacketIn) msg;
        OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();
        fmb.setBufferId(pi.getBufferId())
        .setXid(pi.getXid());

        // set actions
        OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
        actionBuilder.setPort(OFPort.FLOOD);
        fmb.setActions(Collections.singletonList((OFAction) actionBuilder.build()));

        return fmb.build();
    }
    
    private void createHubPacketOut(IOFSwitch sw, OFMessage msg, int port, Ethernet eth, IPv4 ipv4, FloodlightContext cntx, VirtualGatewayInstance gateway) {
//        MacAddress gatewayMac = gateway.getGatewayMac();
        OFPacketIn pi = (OFPacketIn) msg;

        // generate ARP request to destination host
//        IPv4Address dstIP = IPv4Address.of(10,10, 0, 4);
//        IPv4Address intfIpAddress = findInterfaceIP(gateway, dstIP);
        
        MacAddress dstMac = MacAddress.of("00:00:00:00:00:04" );
    	MacAddress srcMac = MacAddress.of("00:00:00:00:00:01" );
        
        Ethernet l2 = new Ethernet();
    	l2.setSourceMACAddress(srcMac);
    	l2.setDestinationMACAddress(dstMac);
    	l2.setEtherType(EthType.IPv4);
    	logger.info("srcMac: {} dstMac: {}", srcMac.toString(), dstMac.toString());
    	
    	IPv4Address dstIp = IPv4Address.of(10, 10, 0, 4);
    	IPv4Address srcIp = IPv4Address.of(10, 10, 0, 1);
    	
    	IPv4 l3 = new IPv4();
    	l3.setSourceAddress(srcIp);
    	l3.setDestinationAddress(dstIp);
    	l3.setTtl((byte) 64);
    	l3.setProtocol(IpProtocol.TCP);
    	logger.info("dstIp: {} srcIp: {}", dstIp.toString(), srcIp.toString());
    	
    	TCP tcp = (TCP) ipv4.getPayload();
    	TransportPort srcPort = tcp.getSourcePort();
    	TransportPort dstPort = tcp.getDestinationPort();
	    
    	TCP l4 = new TCP();
    	l4 = (TCP) tcp.clone();
    	l4.setSourcePort(srcPort);
    	l4.setDestinationPort(dstPort);
//    	l4.setChecksum(tcp.getChecksum());
    	logger.info("srcPort: {} dstPort: {}", srcPort.toString(), dstPort.toString());
    	
    	Data l7 = new Data();
    	
//    	OFPacketIn pi = (OFPacketIn) msg;
    	if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
            byte[] packetData = pi.getData();
            l7.setData(packetData);
        }
    	
    	l2.setPayload(l3);
    	l3.setPayload(l4);
    	l4.setPayload(l7);
    	
    	byte[] serializedData = l2.serialize();

//        // Set src MAC to virtual gateway MAC, set dst MAC to broadcast
//        IPacket tcpPacket = new Ethernet()
//                .setSourceMACAddress(MacAddress.of("00:00:00:00:00:01"))
//                .setDestinationMACAddress(MacAddress.of("00:00:00:00:00:04"))
//                .setEtherType(EthType.IPv4)
//                .setVlanID(eth.getVlanID())
//                .setPriorityCode(eth.getPriorityCode())
//                .setPayload(
//                        new TCP()
//                        		.setSourcePort(1)
//                        		.setDestinationPort(4));
//        Data l7 = new Data();
//    	
////    	if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
//        byte[] packetData = pi.getData();
//        l7.setData(packetData);
////        }
//    	tcpPacket.setPayload(l7);
//        byte[] data = tcpPacket.serialize();

        OFPort inPort = OFMessageUtils.getInPort(pi);

        OFFactory factory = sw.getOFFactory();
        OFPacketOut.Builder packetOut = factory.buildPacketOut();

        // Set Actions
        List<OFAction> actions = new ArrayList<>();

//        Set<OFPort> broadcastPorts = this.topologyService.getSwitchBroadcastPorts(sw.getId());
        Set<OFPort> broadcastPorts = new HashSet<OFPort>();//this.topologyService.getSwitchBroadcastPorts(sw.getId());
//      broadcastPorts.add(OFPort.of(3));
      broadcastPorts.add(OFPort.of(4));
//      broadcastPorts.add(OFPort.of(5));
        if (broadcastPorts.isEmpty()) {
            log.debug("No broadcast ports found. Using FLOOD output action");
            broadcastPorts = Collections.singleton(OFPort.FLOOD);
        }

        for (OFPort p : broadcastPorts) {
            if (p.equals(inPort)) continue;
            actions.add(factory.actions().output(p, Integer.MAX_VALUE));
        }
        packetOut.setActions(actions);

        // set buffer-id, in-port and packet-data based on packet-in
        packetOut.setBufferId(OFBufferId.NO_BUFFER);
        OFMessageUtils.setInPort(packetOut, inPort);
        packetOut.setData(serializedData);

        if (log.isTraceEnabled()) {
            log.trace("Writing flood PacketOut switch={} packet-in={} packet-out={}",
                    new Object[] {sw, pi, packetOut.build()});
        }
        sw.write(packetOut.build());
//        messageDamper.write(sw, packetOut.build());
    	
//    	OFPacketIn pi = (OFPacketIn) msg;
//    	OFPort inPort = OFMessageUtils.getInPort(pi);
//    	IPv4Address dstIp = ipv4.getDestinationAddress();
//        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
//        List<OFAction> actions = new ArrayList<>();
//        Set<OFPort> broadcastPorts = new HashSet<OFPort>();//this.topologyService.getSwitchBroadcastPorts(sw.getId());
//        logger.info(inPort.toString());
//        broadcastPorts.add(OFPort.of(3));
//        broadcastPorts.add(OFPort.of(4));
//        broadcastPorts.add(OFPort.of(5));
//        
//        if (broadcastPorts.isEmpty()) {
//            log.debug("No broadcast ports found. Using FLOOD output action");
//            broadcastPorts = Collections.singleton(OFPort.FLOOD);
//        }
//        
//        for (OFPort p : broadcastPorts) {
//            if (p.equals(inPort)) continue;
//            IPv4Address ip = IPv4Address.of(10, 10, 0, p.getPortNumber());
//            if (dstIp.compareTo(ip) == 0) continue;
//            actions.add(sw.getOFFactory().actions().output(p, Integer.MAX_VALUE));
//        }
//        pob.setActions(actions);
//
//        // set buffer-id, in-port and packet-data based on packet-in
//        pob.setBufferId(OFBufferId.NO_BUFFER);
//        OFMessageUtils.setInPort(pob, inPort);
//        
//        pob.setData(pi.getData());
//
//        if (log.isTraceEnabled()) {
//            logger.info("Writing flood PacketOut switch={} packet-in={} packet-out={}",
//                    new Object[] {sw, pi, pob.build()});
//        }
//        sw.write(pob.build());

//    	MacAddress dstMac = MacAddress.of("00:00:00:00:00:0"+ String.valueOf(port) );
//    	MacAddress srcMac = eth.getSourceMACAddress();
//    	if(port == 1) {
//    		srcMac = MacAddress.of("00:00:00:00:00:03");
//    	}
//    	Ethernet l2 = new Ethernet();
//    	l2.setSourceMACAddress(srcMac);
//    	l2.setDestinationMACAddress(dstMac);
//    	l2.setEtherType(EthType.IPv4);
//    	logger.info("srcMac: {} dstMac: {}", srcMac.toString(), dstMac.toString());
//    	
//    	IPv4Address dstIp = IPv4Address.of(10, 10, 0, port);
//    	IPv4Address srcIp = ipv4.getSourceAddress();
//    	if(port == 1) {
//    		srcIp = IPv4Address.of(10, 10, 0, 3);
//    	}
//    	IPv4 l3 = new IPv4();
//    	l3.setSourceAddress(srcIp);
//    	l3.setDestinationAddress(dstIp);
//    	l3.setTtl((byte) 64);
//    	l3.setProtocol(IpProtocol.TCP);
//    	logger.info("dstIp: {} srcIp: {}", dstIp.toString(), srcIp.toString());
//    	
//    	TCP tcp = (TCP) ipv4.getPayload();
//	      
//        /* Various getters and setters are exposed in TCP */
//        TransportPort srcPort = tcp.getSourcePort();
//        TransportPort dstPort = tcp.getDestinationPort();
//        if(port == 1)
//        {
//        	srcPort = TransportPort.of(3);
//        }
//    	TCP l4 = new TCP();
//    	l4.setSourcePort(srcPort);
//    	l4.setDestinationPort(dstPort);
//    	logger.info("srcPort: {} dstPort: {}", srcPort.toString(), dstPort.toString());
//    	
//    	Data l7 = new Data();
//    	
//    	OFPacketIn pi = (OFPacketIn) msg;
//    	if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
//            byte[] packetData = pi.getData();
//            l7.setData(packetData);
//        }
//    	
//    	l2.setPayload(l3);
//    	l3.setPayload(l4);
//    	l4.setPayload(l7);
//    	
//    	byte[] serializedData = l2.serialize();
//    	
//    	OFPacketOut po = sw.getOFFactory().buildPacketOut() /* mySwitch is some IOFSwitch object */
//					    .setData(serializedData)
//					    .setActions(Collections.singletonList((OFAction) sw.getOFFactory().actions().output(OFPort.FLOOD, 0xffFFffFF)))
//					    .setInPort(OFPort.of(srcPort.getPort()))
//					    .build();
//		 
//    	logger.info("Writing flood PacketOut switch={} packet-in={} packet-out={}",
//              new Object[] {sw, pi, po});
//		sw.write(po);
    	
//    	OFPacketIn pi = (OFPacketIn) msg;
//        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
//        pob.setBufferId(pi.getBufferId()).setXid(pi.getXid()).setInPort((pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT)));
//        
//        // set actions
//        OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
//        if(port == 0) {
//        	actionBuilder.setPort(OFPort.FLOOD);
//        }
//	    else {
//	        OFPort outPort = OFPort.of(port);
//	        actionBuilder.setPort(outPort);
//	    }
//        pob.setActions(Collections.singletonList((OFAction) actionBuilder.build()));
//
//        // set data if it is included in the packetin
//        if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
//            byte[] packetData = pi.getData();
//            pob.setData(packetData);
//        }
//        return pob.build();  
    }
    
    public IPv4Address findInterfaceIP(VirtualGatewayInstance gateway, IPv4Address dstIP) {
        Optional<VirtualGatewayInterface> intf = gateway.findGatewayInft(dstIP);
        if (intf.isPresent()) {
            return intf.get().getIp();
        }
        else {
            return null;
        }
    }
    
    protected void doFlood(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
        OFPort inPort = OFMessageUtils.getInPort(pi);
        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        List<OFAction> actions = new ArrayList<>();
        Set<OFPort> broadcastPorts = this.topologyService.getSwitchBroadcastPorts(sw.getId());

        if (broadcastPorts.isEmpty()) {
            log.debug("No broadcast ports found. Using FLOOD output action");
            broadcastPorts = Collections.singleton(OFPort.FLOOD);
        }

        for (OFPort p : broadcastPorts) {
            if (p.equals(inPort)) continue;
            actions.add(sw.getOFFactory().actions().output(p, Integer.MAX_VALUE));
        }
        pob.setActions(actions);

        // set buffer-id, in-port and packet-data based on packet-in
        pob.setBufferId(OFBufferId.NO_BUFFER);
        OFMessageUtils.setInPort(pob, inPort);
        pob.setData(pi.getData());

        if (log.isTraceEnabled()) {
            log.trace("Writing flood PacketOut switch={} packet-in={} packet-out={}",
                    new Object[] {sw, pi, pob.build()});
        }
        messageDamper.write(sw, pob.build());

        return;
    }
    
    protected void doL2ForwardFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx, boolean requestFlowRemovedNotifn) {
        OFPort srcPort = OFMessageUtils.getInPort(pi);
        DatapathId srcSw = sw.getId();
        IDevice dstDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
        IDevice srcDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);
        
        if (dstDevice == null) {
            log.debug("Destination device unknown. Flooding packet");
            doFlood(sw, pi, decision, cntx);
            return;
        }

        if (srcDevice == null) {
            log.error("No device entry found for source device. Is the device manager running? If so, report bug.");
            return;
        }

        /* Some physical switches partially support or do not support ARP flows */
        if (FLOOD_ALL_ARP_PACKETS &&
                IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD).getEtherType()
                        == EthType.ARP) {
            log.debug("ARP flows disabled in Forwarding. Flooding ARP packet");
            doFlood(sw, pi, decision, cntx);
            return;
        }

        /* This packet-in is from a switch in the path before its flow was installed along the path */
        if (!topologyService.isEdge(srcSw, srcPort)) {
            log.debug("Packet destination is known, but packet was not received on an edge port (rx on {}/{}). Flooding packet", srcSw, srcPort);
            doFlood(sw, pi, decision, cntx);
            return;
        }
                /*
         * Search for the true attachment point. The true AP is
         * not an endpoint of a link. It is a switch port w/o an
         * associated link. Note this does not necessarily hold
         * true for devices that 'live' between OpenFlow islands.
         *
         * TODO Account for the case where a device is actually
         * attached between islands (possibly on a non-OF switch
         * in between two OpenFlow switches).
         */
        SwitchPort dstAp = null;
        for (SwitchPort ap : dstDevice.getAttachmentPoints()) {
            if (topologyService.isEdge(ap.getNodeId(), ap.getPortId())) {
                dstAp = ap;
                break;
            }
        }

                /* Validate that the source and destination are not on the same switch port */
        if (sw.getId().equals(dstAp.getNodeId()) && srcPort.equals(dstAp.getPortId())) {
            log.debug("Both source and destination are on the same switch/port {}/{}. Dropping packet", sw.toString(), srcPort);
            return;
        }

        U64 flowSetId = flowSetIdRegistry.generateFlowSetId();
        U64 cookie = makeForwardingCookie(decision, flowSetId);
        Path path = routingEngineService.getPath(srcSw,
                srcPort,
                dstAp.getNodeId(),
                dstAp.getPortId());

        Match m = createMatchFromPacket(sw, srcPort, pi, cntx);

        if (! path.getPath().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("pushRoute inPort={} route={} " +
                                "destination={}:{}",
                        new Object[] { srcPort, path,
                                dstAp.getNodeId(),
                                dstAp.getPortId()});
                log.debug("Creating flow rules on the route, match rule: {}", m);
            }

            pushRoute(path, m, pi, sw.getId(), cookie,
                    cntx, requestFlowRemovedNotifn,
                    OFFlowModCommand.ADD, false);

            /*
             * Register this flowset with ingress and egress ports for link down
             * flow removal. This is done after we push the path as it is blocking.
             */
            for (NodePortTuple npt : path.getPath()) {
                flowSetIdRegistry.registerFlowSetId(npt, flowSetId);
            }
        } /* else no path was found */
    }
    
    protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort, OFPacketIn pi, FloodlightContext cntx) {
        // The packet in match will only contain the port number.
        // We need to add in specifics for the hosts we're routing between.
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

        VlanVid vlan = null;      
        if (pi.getVersion().compareTo(OFVersion.OF_11) > 0 && /* 1.0 and 1.1 do not have a match */
                pi.getMatch().get(MatchField.VLAN_VID) != null) { 
            vlan = pi.getMatch().get(MatchField.VLAN_VID).getVlanVid(); /* VLAN may have been popped by switch */
        }
        if (vlan == null) {
            vlan = VlanVid.ofVlan(eth.getVlanID()); /* VLAN might still be in packet */
        }
        
        MacAddress srcMac = eth.getSourceMACAddress();
        MacAddress dstMac = eth.getDestinationMACAddress();

        Match.Builder mb = sw.getOFFactory().buildMatch();
        if (FLOWMOD_DEFAULT_MATCH_IN_PORT) {
            mb.setExact(MatchField.IN_PORT, inPort);
        }

        if (FLOWMOD_DEFAULT_MATCH_MAC) {
            if (FLOWMOD_DEFAULT_MATCH_MAC_SRC) {
                mb.setExact(MatchField.ETH_SRC, srcMac);
            }
            if (FLOWMOD_DEFAULT_MATCH_MAC_DST) {
                mb.setExact(MatchField.ETH_DST, dstMac);
            }
        }

        if (FLOWMOD_DEFAULT_MATCH_VLAN) {
            if (!vlan.equals(VlanVid.ZERO)) {
                mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
            }
        }

        // TODO Detect switch type and match to create hardware-implemented flow
        if (eth.getEtherType() == EthType.IPv4) { /* shallow check for equality is okay for EthType */
            IPv4 ip = (IPv4) eth.getPayload();
            IPv4Address srcIp = ip.getSourceAddress();
            IPv4Address dstIp = ip.getDestinationAddress();

            if (FLOWMOD_DEFAULT_MATCH_IP) {
                mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
                if (FLOWMOD_DEFAULT_MATCH_IP_SRC) {
                    mb.setExact(MatchField.IPV4_SRC, srcIp);
                }
                if (FLOWMOD_DEFAULT_MATCH_IP_DST) {
                    mb.setExact(MatchField.IPV4_DST, dstIp);
                }
            }

            if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
                /*
                 * Take care of the ethertype if not included earlier,
                 * since it's a prerequisite for transport ports.
                 */
                if (!FLOWMOD_DEFAULT_MATCH_IP) {
                    mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
                }

                if (ip.getProtocol().equals(IpProtocol.TCP)) {
                    TCP tcp = (TCP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
                        mb.setExact(MatchField.TCP_SRC, tcp.getSourcePort());
                    }
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
                        mb.setExact(MatchField.TCP_DST, tcp.getDestinationPort());
                    }
		    if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_15) >= 0){
			 if(FLOWMOD_DEFAULT_MATCH_TCP_FLAG){
	                        mb.setExact(MatchField.TCP_FLAGS, U16.of(tcp.getFlags()));
	                 }
		    }
                    else if(sw.getSwitchDescription().getHardwareDescription().toLowerCase().contains("open vswitch") && (
                       Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase().split("\\.")[0]) > 2  || (
                       Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase().split("\\.")[0]) == 2 &&
                       Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase().split("\\.")[1]) >= 1 ))
		      ){
	                    if(FLOWMOD_DEFAULT_MATCH_TCP_FLAG){
	                        mb.setExact(MatchField.OVS_TCP_FLAGS, U16.of(tcp.getFlags()));
	                    }
                    }
                } else if (ip.getProtocol().equals(IpProtocol.UDP)) {
                    UDP udp = (UDP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
                        mb.setExact(MatchField.UDP_SRC, udp.getSourcePort());
                    }
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
                        mb.setExact(MatchField.UDP_DST, udp.getDestinationPort());
                    }
                }
            }
        } else if (eth.getEtherType() == EthType.ARP) { /* shallow check for equality is okay for EthType */
            mb.setExact(MatchField.ETH_TYPE, EthType.ARP);
        } else if (eth.getEtherType() == EthType.IPv6) {
            IPv6 ip = (IPv6) eth.getPayload();
            IPv6Address srcIp = ip.getSourceAddress();
            IPv6Address dstIp = ip.getDestinationAddress();

            if (FLOWMOD_DEFAULT_MATCH_IP) {
                mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
                if (FLOWMOD_DEFAULT_MATCH_IP_SRC) {
                    mb.setExact(MatchField.IPV6_SRC, srcIp);
                }
                if (FLOWMOD_DEFAULT_MATCH_IP_DST) {
                    mb.setExact(MatchField.IPV6_DST, dstIp);
                }
            }

            if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
                /*
                 * Take care of the ethertype if not included earlier,
                 * since it's a prerequisite for transport ports.
                 */
                if (!FLOWMOD_DEFAULT_MATCH_IP) {
                    mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
                }

                if (ip.getNextHeader().equals(IpProtocol.TCP)) {
                    TCP tcp = (TCP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
                        mb.setExact(MatchField.TCP_SRC, tcp.getSourcePort());
                    }
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
                        mb.setExact(MatchField.TCP_DST, tcp.getDestinationPort());
                    }
		    if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_15) >= 0){
		        if(FLOWMOD_DEFAULT_MATCH_TCP_FLAG){
			   mb.setExact(MatchField.TCP_FLAGS, U16.of(tcp.getFlags()));
		        }
		    }
                    else if(
                    sw.getSwitchDescription().getHardwareDescription().toLowerCase().contains("open vswitch") && (
                    Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase().split("\\.")[0]) > 2  || (
                    Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase().split("\\.")[0]) == 2 &&
                    Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase().split("\\.")[1]) >= 1 ))
                    ){
	                    if(FLOWMOD_DEFAULT_MATCH_TCP_FLAG){
	                        mb.setExact(MatchField.OVS_TCP_FLAGS, U16.of(tcp.getFlags()));
	                    }
                    }
                } else if (ip.getNextHeader().equals(IpProtocol.UDP)) {
                    UDP udp = (UDP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
                        mb.setExact(MatchField.UDP_SRC, udp.getSourcePort());
                    }
                    if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
                        mb.setExact(MatchField.UDP_DST, udp.getDestinationPort());
                    }
                }
            }
        }
        return mb.build();
    }
    
    protected U64 makeForwardingCookie(IRoutingDecision decision, U64 flowSetId) {
        long user_fields = 0;

        U64 decision_cookie = (decision == null) ? null : decision.getDescriptor();
        if (decision_cookie != null) {
            user_fields |= AppCookie.extractUser(decision_cookie) & DECISION_MASK;
        }

        if (flowSetId != null) {
            user_fields |= AppCookie.extractUser(flowSetId) & FLOWSET_MASK;
        }

        // TODO: Mask in any other required fields here

        if (user_fields == 0) {
            return DEFAULT_FORWARDING_COOKIE;
        }
        return AppCookie.makeCookie(FORWARDING_APP_ID, user_fields);
    }
    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return false;
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
    }
    
    public Optional<VirtualGatewayInstance> getGatewayInstance(DatapathId dpid) {
        return l3manager.getVirtualGateway(dpid);
    }

    // IFloodlightModule
    
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        // We don't provide any services, return null
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService>
            getServiceImpls() {
        // We don't provide any services, return null
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>>
            getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        logger = LoggerFactory.getLogger(Hub.class);
        l3manager = new L3RoutingManager();
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
    }

	@Override
	public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
			FloodlightContext cntx) {
		// TODO Auto-generated method stub
		return null;
	}
}
