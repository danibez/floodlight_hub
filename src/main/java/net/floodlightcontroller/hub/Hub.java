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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.VlanVid;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu) - 04/04/10
 */
public class Hub implements IFloodlightModule, IOFMessageListener {
	private enum HubType {USE_PACKET_OUT, USE_FLOW_MOD};
	protected static Logger logger;
    private IFloodlightProviderService floodlightProvider;
    private int count=0;

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
	                
	                String ipDst = dstIp.toString();
	                logger.info("ipDst: {}",ipDst);
	                if ((ipDst.compareTo("10.10.0.3") == 0) || (ipDst.compareTo("10.10.0.4") == 0) || (ipDst.compareTo("10.10.0.5") == 0)) {  
                		if(ipv4.getProtocol() == IpProtocol.TCP) {
		                    /* We got a TCP packet; get the payload from IPv4 */
		                    TCP tcp = (TCP) ipv4.getPayload();
		      
		                    /* Various getters and setters are exposed in TCP */
		                    TransportPort srcPort = tcp.getSourcePort();
		                    TransportPort dstPort = tcp.getDestinationPort();
	//	                    short flags = tcp.getFlags();
		                    logger.info("PORT: {} ",dstPort.getPort());
		                    OFMessage outMessage;
		                	OFMessage outMessage2;
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
		            	    		MacAddress dstMac = eth.getDestinationMACAddress();
		            	            outMessage = createHubPacketOut(sw, msg, 3, dstMac);
		            	            outMessage2 = createHubPacketOut(sw, msg, 4, dstMac);
		            	            outMessage3 = createHubPacketOut(sw, msg, 5, dstMac);
		            	            break;
		                	}
		                    sw.write(outMessage);
		                    sw.write(outMessage2);
		                    sw.write(outMessage3);
                		}
	                }
//	                else
//	                {
//	                	if(ipv4.getProtocol() == IpProtocol.TCP) {
//		                    /* We got a TCP packet; get the payload from IPv4 */
//		                    TCP tcp = (TCP) ipv4.getPayload();
//		                    logger.info("");
//		                    /* Various getters and setters are exposed in TCP */
//		                    TransportPort srcPort = tcp.getSourcePort();
//		                    TransportPort dstPort = tcp.getDestinationPort();
//	//	                    short flags = tcp.getFlags();
//		                     
//		                    OFMessage outMessage;
//		                	OFMessage outMessage2;
//		                	OFMessage outMessage3;
//		                	HubType ht = HubType.USE_PACKET_OUT;
//		                	switch (ht) {
//		            	    	case USE_FLOW_MOD:
//		            	            outMessage = createHubFlowMod(sw, msg);
//		            	            outMessage2 = createHubFlowMod(sw, msg);
//		            	            outMessage3 = createHubFlowMod(sw, msg);
//		            	            break;
//		            	        default:
//		            	    	case USE_PACKET_OUT:
//		            	            outMessage = createHubPacketOut(sw, msg, dstPort.getPort());
//		            	            outMessage2 = createHubPacketOut(sw, msg, dstPort.getPort());
//		            	            outMessage3 = createHubPacketOut(sw, msg, dstPort.getPort());
//		            	            break;
//		                	}
//		                    sw.write(outMessage);
//		                    sw.write(outMessage2);
//		                    sw.write(outMessage3);
//                		}
//	                }
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
    
    private OFMessage createHubPacketOut(IOFSwitch sw, OFMessage msg, int port, MacAddress dstMac) {
    	OFPacketIn pi = (OFPacketIn) msg;
        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        pob.setBufferId(pi.getBufferId()).setXid(pi.getXid()).setInPort((pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT)));
        
        // set actions
        OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
        if(port == 0) {
        	actionBuilder.setPort(OFPort.FLOOD);
        }
	    else {
	        OFPort outPort = OFPort.of(port);
	        actionBuilder.setPort(outPort);
	    }
        pob.setActions(Collections.singletonList((OFAction) actionBuilder.build()));

        // set data if it is included in the packetin
        if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
            byte[] packetData = pi.getData();
            pob.setData(packetData);
        }
        return pob.build();  
    }

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return false;
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
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
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
    }
}
