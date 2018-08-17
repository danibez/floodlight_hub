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

package net.floodlightcontroller.routing;

import java.util.*;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.util.*;

import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Abstract base class for implementing a forwarding module.  Forwarding is
 * responsible for programming flows to a switch in response to a policy
 * decision.
 */
public abstract class ForwardingBase implements IOFMessageListener {
    protected static Logger log = LoggerFactory.getLogger(ForwardingBase.class);

    public static int FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
    public static int FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
    public static int FLOWMOD_DEFAULT_PRIORITY = 1; // 0 is the default table-miss flow in OF1.3+, so we need to use 1

    protected static TableId FLOWMOD_DEFAULT_TABLE_ID = TableId.ZERO;

    protected static boolean FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG = false;

    protected static boolean FLOWMOD_DEFAULT_MATCH_IN_PORT = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_VLAN = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_MAC = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_IP = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_TRANSPORT = true;
    
    protected static boolean FLOWMOD_DEFAULT_MATCH_MAC_SRC = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_MAC_DST = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_IP_SRC = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_IP_DST = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST = true;
    protected static boolean FLOWMOD_DEFAULT_MATCH_TCP_FLAG = true;

    protected static boolean FLOOD_ALL_ARP_PACKETS = false;

    protected static boolean REMOVE_FLOWS_ON_LINK_OR_PORT_DOWN = true;

    protected IFloodlightProviderService floodlightProviderService;
    protected IOFSwitchService switchService;
    protected IDeviceService deviceManagerService;
    protected IRoutingService routingEngineService;
    protected ITopologyService topologyService;
    protected IDebugCounterService debugCounterService;
    protected ILinkDiscoveryService linkService;
    protected IRestApiService restApiService;

    // flow-mod - for use in the cookie
    public static final int FORWARDING_APP_ID = 2;
    static {
        AppCookie.registerApp(FORWARDING_APP_ID, "forwarding");
    }
    protected static final U64 DEFAULT_FORWARDING_COOKIE = AppCookie.makeCookie(FORWARDING_APP_ID, 0);

    protected OFMessageDamper messageDamper;
    private static int OFMESSAGE_DAMPER_CAPACITY = 10000;
    private static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms
    
    protected void init() {
        messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY,
                EnumSet.of(OFType.FLOW_MOD),
                OFMESSAGE_DAMPER_TIMEOUT);
    }

    protected void startUp() {
        floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
    }

    @Override
    public String getName() {
        return "forwarding";
    }

    /**
     * All subclasses must define this function if they want any specific
     * forwarding action
     *
     * @param sw
     *            Switch that the packet came in from
     * @param pi
     *            The packet that came in
     * @param decision
     *            Any decision made by a policy engine
     */
    public abstract Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, 
            IRoutingDecision decision, FloodlightContext cntx);

    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

        switch (msg.getType()) {
        case PACKET_IN:
            IRoutingDecision decision = null;
            if (cntx != null) {
                decision = RoutingDecision.rtStore.get(cntx, IRoutingDecision.CONTEXT_DECISION);
            }
            return this.processPacketInMessage(sw, (OFPacketIn) msg, decision, cntx);
        default:
            break;
        }
        return Command.CONTINUE;
    }

    /**
     * Push routes from back to front
     * @param route Route to push
     * @param match OpenFlow fields to match on
     * @param srcSwPort Source switch port for the first hop
     * @param dstSwPort Destination switch port for final hop
     * @param cookie The cookie to set in each flow_mod
     * @param cntx The floodlight context
     * @param requestFlowRemovedNotification if set to true then the switch would
     *        send a flow mod removal notification when the flow mod expires
     * @param flowModCommand flow mod. command to use, e.g. OFFlowMod.OFPFC_ADD,
     *        OFFlowMod.OFPFC_MODIFY etc.
     * @return true if a packet out was sent on the first-hop switch of this route
     */
    public boolean pushRoute(Path route, Match match, OFPacketIn pi,
            DatapathId pinSwitch, U64 cookie, FloodlightContext cntx,
            boolean requestFlowRemovedNotification, OFFlowModCommand flowModCommand, boolean packetOutSent) {

        List<NodePortTuple> switchPortList = route.getPath();

        for (int indx = switchPortList.size() - 1; indx > 0; indx -= 2) {
            // indx and indx-1 will always have the same switch DPID.
            DatapathId switchDPID = switchPortList.get(indx).getNodeId();
            IOFSwitch sw = switchService.getSwitch(switchDPID);

            if (sw == null) {
                if (log.isWarnEnabled()) {
                    log.warn("Unable to push route, switch at DPID {} " + "not available", switchDPID);
                }
                return false;
            }

            // need to build flow mod based on what type it is. Cannot set command later
            OFFlowMod.Builder fmb;
            switch (flowModCommand) {
            case ADD:
                fmb = sw.getOFFactory().buildFlowAdd();
                break;
            case DELETE:
                fmb = sw.getOFFactory().buildFlowDelete();
                break;
            case DELETE_STRICT:
                fmb = sw.getOFFactory().buildFlowDeleteStrict();
                break;
            case MODIFY:
                fmb = sw.getOFFactory().buildFlowModify();
                break;
            default:
                log.error("Could not decode OFFlowModCommand. Using MODIFY_STRICT. (Should another be used as the default?)");        
            case MODIFY_STRICT:
                fmb = sw.getOFFactory().buildFlowModifyStrict();
                break;			
            }

            OFActionOutput.Builder aob = sw.getOFFactory().actions().buildOutput();
            List<OFAction> actions = new ArrayList<>();
            Match.Builder mb = MatchUtils.convertToVersion(match, sw.getOFFactory().getVersion());

            // set input and output ports on the switch
            OFPort outPort = switchPortList.get(indx).getPortId();
            OFPort inPort = switchPortList.get(indx - 1).getPortId();
            if (FLOWMOD_DEFAULT_MATCH_IN_PORT) {
                mb.setExact(MatchField.IN_PORT, inPort);
            }
            aob.setPort(outPort);
            aob.setMaxLen(Integer.MAX_VALUE);
            actions.add(aob.build());

            if (FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG || requestFlowRemovedNotification) {
                Set<OFFlowModFlags> flags = new HashSet<>();
                flags.add(OFFlowModFlags.SEND_FLOW_REM);
                fmb.setFlags(flags);
            }

            fmb.setMatch(mb.build())
            .setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
            .setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
            .setBufferId(OFBufferId.NO_BUFFER)
            .setCookie(cookie)
            .setOutPort(outPort)
            .setPriority(FLOWMOD_DEFAULT_PRIORITY);

            FlowModUtils.setActions(fmb, actions, sw);

            /* Configure for particular switch pipeline */
            if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) != 0) {
                fmb.setTableId(FLOWMOD_DEFAULT_TABLE_ID);
            }
                        
            if (log.isTraceEnabled()) {
                log.trace("Pushing Route flowmod routeIndx={} " +
                        "sw={} inPort={} outPort={}",
                        new Object[] {indx,
                                sw,
                                fmb.getMatch().get(MatchField.IN_PORT),
                                outPort });
            }

            if (OFDPAUtils.isOFDPASwitch(sw)) {
                OFDPAUtils.addLearningSwitchFlow(sw, cookie, 
                        FLOWMOD_DEFAULT_PRIORITY, 
                        FLOWMOD_DEFAULT_HARD_TIMEOUT,
                        FLOWMOD_DEFAULT_IDLE_TIMEOUT,
                        fmb.getMatch(), 
                        null, // TODO how to determine output VLAN for lookup of L2 interface group
                        outPort);
            } else {
                messageDamper.write(sw, fmb.build());
            }

            /* Push the packet out the first hop switch */
            if (!packetOutSent && sw.getId().equals(pinSwitch) &&
                    !fmb.getCommand().equals(OFFlowModCommand.DELETE) &&
                    !fmb.getCommand().equals(OFFlowModCommand.DELETE_STRICT)) {
                /* Use the buffered packet at the switch, if there's one stored */
                log.debug("Push packet out the first hop switch");
                pushPacket(sw, pi, outPort, true, cntx);
            }

        }

        return true;
    }

    /**
     * Pushes a packet-out to a switch. The assumption here is that
     * the packet-in was also generated from the same switch. Thus, if the input
     * port of the packet-in and the outport are the same, the function will not
     * push the packet-out.
     * @param sw switch that generated the packet-in, and from which packet-out is sent
     * @param pi packet-in
     * @param outport output port
     * @param useBufferedPacket use the packet buffered at the switch, if possible
     * @param cntx context of the packet
     */
    protected void pushPacket(IOFSwitch sw, OFPacketIn pi, OFPort outport, boolean useBufferedPacket, FloodlightContext cntx) {
        if (pi == null) {
            return;
        }

        // The assumption here is (sw) is the switch that generated the
        // packet-in. If the input port is the same as output port, then
        // the packet-out should be ignored.
        if ((pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT)).equals(outport)) {
            if (log.isDebugEnabled()) {
                log.debug("Attempting to do packet-out to the same " +
                        "interface as packet-in. Dropping packet. " +
                        " SrcSwitch={}, pi={}",
                        new Object[]{sw, pi});
                return;
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("PacketOut srcSwitch={} pi={}",
                    new Object[] {sw, pi});
        }

        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        List<OFAction> actions = new ArrayList<>();
        actions.add(sw.getOFFactory().actions().output(outport, Integer.MAX_VALUE));
        pob.setActions(actions);

        /* Use packet in buffer if there is a buffer ID set */
        if (useBufferedPacket) {
            pob.setBufferId(pi.getBufferId()); /* will be NO_BUFFER if there isn't one */
        } else {
            pob.setBufferId(OFBufferId.NO_BUFFER);
        }
        
        if (pob.getBufferId().equals(OFBufferId.NO_BUFFER)) {
            byte[] packetData = pi.getData();
            pob.setData(packetData);
        }

        OFMessageUtils.setInPort(pob, OFMessageUtils.getInPort(pi));
        messageDamper.write(sw, pob.build());
    }

    /**
     * Write packetout message to sw with output actions to one or more
     * output ports with inPort/outPorts passed in.
     * @param packetData
     * @param sw
     * @param inPort
     * @param ports
     * @param cntx
     */
    public void packetOutMultiPort(byte[] packetData, IOFSwitch sw, 
            OFPort inPort, HashMap<Integer, OFPort[]> flowMap, Set<OFPort> outPorts, FloodlightContext cntx) {
    	
    	Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
    	
    	List<OFAction> actions = new ArrayList<>();

        Iterator<OFPort> j = outPorts.iterator();
        
        OFActions action = sw.getOFFactory().actions();
        OFOxms oxms = sw.getOFFactory().oxms();
        OFPort port;
        while (j.hasNext()) {
        	port = j.next();
        	if(inPort.getPortNumber() < 3) {
        		if(port.getPortNumber() <= 2) continue;
//        		==================10.10.0.6:5003/4/5<--->10.10.0.3/.4/.5:5001===================
//        		log.info("front: {}",port.getPortNumber());
        		OFActionSetField setNwDst = action.buildSetField()
    			        .setField(
    			            oxms.buildIpv4Dst()
    			            .setValue(IPv4Address.of("10.10.0."+port.getPortNumber()))
    			            .build()
    			        )
    			        .build();
            	OFActionSetField setDlDst = action.buildSetField()
            		    .setField(
            		        oxms.buildEthDst()
            		        .setValue(MacAddress.of("00:00:00:00:00:0"+port.getPortNumber()))
            		        .build()
            		    )
            		    .build();
//            	OFActionSetField setTcpDst = action.buildSetField()
//	        			.setField(
//	        				oxms.buildTcpDst()
//	        				.setValue(TransportPort.of(5000+port.getPortNumber()))
//	        				.build()
//	        			)
//						.build();
            	
            	OFActionSetField setNwSrc = action.buildSetField()
				        .setField(
				            oxms.buildIpv4Src()
				            .setValue(IPv4Address.of("10.10.0.6"))
				            .build()
				          //+inPort.getPortNumber()))
				        )
				        .build();
	        	OFActionSetField setDlSrc = action.buildSetField()
	        		    .setField(
	        		        oxms.buildEthSrc()
	        		        .setValue(MacAddress.of("12:34:56:78:90:12"))
	        		        .build()
	        		      //+inPort.getPortNumber()))
	        		    )
	        		    .build();
//	        	OFActionSetField setTcpSrc = action.buildSetField()
//	        			.setField(
//	        				oxms.buildTcpSrc()
//	        				.setValue(TransportPort.of(5000+port.getPortNumber()))
//	        				.build()
//	        			)
//						.build();
            	actions.add(setDlDst);
            	actions.add(setNwDst);
            	actions.add(setDlSrc);
            	actions.add(setNwSrc);
//            	actions.add(setTcpSrc);
                actions.add(sw.getOFFactory().actions().output(OFPort.of(3), 0));
        	}
        	else{
//        		==================10.10.0.6:5001<--->10.10.0.1:X===================
        		
//        		log.info("port: {}",port.getPortNumber());
//        		if(port.getPortNumber() > 2) continue;
//        		log.info("inPort:{}, port:{}", inPort.getPortNumber(), port);
//        		IPv4 ipv4 = (IPv4) eth.getPayload();
//        		IPv4Address ipSrc = ipv4.getSourceAddress();
//        		log.info("srcIP: {}, dstIp: {}",ipSrc.toString(), ipv4.getDestinationAddress().toString());
//        		if (ipSrc.toString().compareTo("10.10.0."+String.valueOf(masterPort)) == 0) {
////        			log.info("Break");
//        			actions.add(sw.getOFFactory().actions().output(port, 0));
//        			break;
//        		}
//        		log.info("back: {}",port.getPortNumber());
	        	OFActionSetField setNwDst = action.buildSetField()
				        .setField(
				            oxms.buildIpv4Dst()
				            .setValue(IPv4Address.of("10.10.0."+(port.getPortNumber())))
				            .build()
				          //+inPort.getPortNumber()))
				        )
				        .build();
	        	OFActionSetField setDlDst = action.buildSetField()
	        		    .setField(
	        		        oxms.buildEthDst()
	        		        .setValue(MacAddress.of("00:00:00:00:00:0"+(port.getPortNumber())))//12:34:56:78:90:12
	        		        .build()
	        		      //+inPort.getPortNumber()))
	        		    )
	        		    .build();
//	        	OFActionSetField setTcpDst = action.buildSetField()
//	        			.setField(
//	        				oxms.buildTcpDst()
//	        				.setValue(initialPort)
//	        				.build()
//	        			)
//						.build();
	        	
	        	OFActionSetField setNwSrc = action.buildSetField()
				        .setField(
				            oxms.buildIpv4Src()
				            .setValue(IPv4Address.of("10.10.0.6"))
				            .build()
				          //+inPort.getPortNumber()))
				        )
				        .build();
	        	OFActionSetField setDlSrc = action.buildSetField()
	        		    .setField(
	        		        oxms.buildEthSrc()
	        		        .setValue(MacAddress.of("12:34:56:78:90:12"))
	        		        .build()
	        		      //+inPort.getPortNumber()))
	        		    )
	        		    .build();
	        	actions.add(setDlDst);
            	actions.add(setNwDst);
            	actions.add(setDlSrc);
            	actions.add(setNwSrc);
//            	actions.add(setTcpDst);
	            actions.add(sw.getOFFactory().actions().output(port, 0));
	            break;
        	}
        }
        
        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        pob.setActions(actions);

        pob.setBufferId(OFBufferId.NO_BUFFER);
        OFMessageUtils.setInPort(pob, inPort);
        pob.setData(packetData);

        if (log.isTraceEnabled()) {
            log.trace("write broadcast packet on switch-id={} " +
                    "interfaces={} packet-out={}",
                    new Object[] {sw.getId(), outPorts, pob.build()});
        }
        messageDamper.write(sw, pob.build());
    }

//    private void createOutPacket() {
//    	/* Compose L2 packet. */
//    	Ethernet eth = new Ethernet();
//    	eth.setSourceMACAddress(MacAddress.of(1));
//    	eth.setDestinationMACAddress(MacAddress.of(2));
//    	
//    	/* Compose L3 packet. */
//    	IPv4 ipv4 = new IPv4();
//    	ipv4.setSourceAddress(IPv4Address.of("192.168.1.1"));
//    	ipv4.setDestinationAddress(IPv4Address.of(0xffFFffFF));
//		
//    	/* Set L2's payload as the L3 packet. */
//    	eth.setPayload(ipv4);
//    	
//    	/* Specify the switch port(s) which the packet should be sent out. */
//    	OFActionOutput output = myFactory.actions().buildOutput()
//    	    .setPort(OFPort.FLOOD)
//    	    .build();
//    	 
//    	/* 
//    	 * Compose the OFPacketOut with the above Ethernet packet as the 
//    	 * payload/data, and the specified output port(s) as actions.
//    	 */
//    	OFPacketOut myPacketOut = factory.buildPacketOut()
//    	    .setData(eth.serialize())
//    	    .setBufferId(OFBufferId.NO_BUFFER)
//    	   .setActions(Collections.singletonList((OFAction) output))
//    	    .build();
//	}

	/**
     * @param masterPort 
	 * @see packetOutMultiPort
     * Accepts a PacketIn instead of raw packet data. Note that the inPort
     * and switch can be different than the packet in switch/port
     */
    public void packetOutMultiPort(OFPacketIn pi, IOFSwitch sw,
            OFPort inPort, HashMap<Integer, OFPort[]> flowMap, Set<OFPort> outPorts, FloodlightContext cntx) {
        packetOutMultiPort(pi.getData(), sw, inPort, flowMap, outPorts, cntx);
    }

    /**
     * @see packetOutMultiPort
     * Accepts an IPacket instead of raw packet data. Note that the inPort
     * and switch can be different than the packet in switch/port
     */
    public void packetOutMultiPort(IPacket packet, IOFSwitch sw,
            OFPort inPort, Set<OFPort> outPorts, FloodlightContext cntx) {
        packetOutMultiPort(packet.serialize(), sw, inPort, null, outPorts, cntx);
    }

    public boolean blockHost(IOFSwitchService switchService,
            SwitchPort sw_tup, MacAddress host_mac, short hardTimeout, U64 cookie) {

        if (sw_tup == null) {
            return false;
        }

        IOFSwitch sw = switchService.getSwitch(sw_tup.getNodeId());
        if (sw == null) {
            return false;
        }

        OFPort inputPort = sw_tup.getPortId();
        if (log.isDebugEnabled()) {
            log.debug("blockHost sw={} port={} mac={}",
                    new Object[] { sw, sw_tup.getPortId(), host_mac.getLong() });
        }

        // Create flow-mod based on packet-in and src-switch
        OFFlowMod.Builder fmb = sw.getOFFactory().buildFlowAdd();

        Match.Builder mb = sw.getOFFactory().buildMatch();
        List<OFAction> actions = new ArrayList<OFAction>(); // Set no action to drop
        mb.setExact(MatchField.IN_PORT, inputPort);
        if (host_mac.getLong() != -1L) {
            mb.setExact(MatchField.ETH_SRC, host_mac);
        }

        fmb.setCookie(cookie)
        .setHardTimeout(hardTimeout)
        .setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
        .setPriority(FLOWMOD_DEFAULT_PRIORITY)
        .setBufferId(OFBufferId.NO_BUFFER)
        .setMatch(mb.build());

        FlowModUtils.setActions(fmb, actions, sw);

        if (log.isDebugEnabled()) {
            log.debug("write drop flow-mod sw={} match={} flow-mod={}",
                    new Object[] { sw, mb.build(), fmb.build() });
        }

        messageDamper.write(sw, fmb.build());

        return true;
    }

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return (type.equals(OFType.PACKET_IN) && (name.equals("topology") || name.equals("devicemanager")));
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
    }

}
