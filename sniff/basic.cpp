#include <tins/tins.h>
#include <iostream>
#include <stddef.h>
#include <string>

#include <sstream>
#include <iomanip>
#include <stdint.h>
#include <amqp_tcp_socket.h>
#include <amqp.h>
#include <amqp_framing.h>

using namespace Tins;
using namespace std;

map<int, string> portMap;


// class Client
// {
// 	string ipAddress;
// 	string user;
// 	amqp_connection_state_t *connPub = NULL;
// 	amqp_connection_state_t *connSub = NULL;
// };


// void PublishRabbitMQ()
// {
// 	std_msgs::Float64 msg;
// 	msg.data = message;
// 	amqp_basic_properties_t props;
// 	props._flags = AMQP_BASIC_CONTENT_TYPE_FLAG | AMQP_BASIC_DELIVERY_MODE_FLAG;
// 	props.content_type = amqp_cstring_bytes("text/plain");
// 	props.delivery_mode = 2; /* persistent delivery mode */

// 	// ================================================================================
// 	namespace ser = ros::serialization;
// 	size_t serial_size = ros::serialization::serializationLength(msg);
// 	boost::shared_array<uint8_t> buffer(new uint8_t[serial_size]);

// 	ser::OStream stream(buffer.get(), serial_size);
// 	ser::serialize(stream, msg);

// 	std::string s(buffer.get(), buffer.get()+serial_size);

// 	amqp_bytes_t data;
// 	data.len = serial_size;
// 	data.bytes = (void*)s.data();
// 	// ================================================================================

// 	die_on_error(amqp_basic_publish(*conn,
// 									1,
// 									amqp_cstring_bytes(exchange),
// 									amqp_cstring_bytes(queue),
// 									0,
// 									0,
// 									&props,
// 									data),
// 				"Publishing");
// }


// void amqp_new_connection(amqp_connection_state_t *conn, const char* exchange, bool declare_queue)
// {
// 	amqp_socket_t *socket = NULL;
// 	int status;

// 	socket = amqp_tcp_socket_new(*conn);
// 	if (!socket) {
// 		die("creating TCP socket");
// 	}

// 	status = amqp_socket_open(socket, (cli->ipAddress).c_str(), 5672);
// 	if (status) {
// 		die("opening TCP socket");
// 	}
// }

// void amqp_declare_exchange(amqp_connection_state_t *conn, const char* exchange, bool declare_queue)
// {
// 	die_on_amqp_error(amqp_login(*conn, "/", 0, 131072, 0, AMQP_SASL_METHOD_PLAIN, cli->user.c_str(), cli->user.c_str()),
// 	                "Logging in");
// 	amqp_channel_open(*conn, 1);
// 	die_on_amqp_error(amqp_get_rpc_reply(*conn), "Opening channel");

// 	amqp_exchange_declare(*conn, 1, amqp_cstring_bytes(exchange), amqp_cstring_bytes("topic"),
// 	                  0, 0, 0, 0, amqp_empty_table);
// 	die_on_amqp_error(amqp_get_rpc_reply(*conn), "Declaring exchange");
// }

// void amqp_declare_queue()
// {
// 	amqp_bytes_t queuename = amqp_cstring_bytes(queue);
// 	amqp_queue_declare_ok_t *r = amqp_queue_declare(*conn, 1, queuename, 0, 0, 0, 0,
// 	                             amqp_empty_table);
// 	die_on_amqp_error(amqp_get_rpc_reply(*conn), "Declaring queue");
// 	queuename = amqp_bytes_malloc_dup(r->queue);
// 	if (queuename.bytes == NULL) {
// 		fprintf(stderr, "Out of memory while copying queue name");
// 	}

// 	amqp_queue_bind(*conn, 1, queuename, amqp_cstring_bytes(exchange), amqp_cstring_bytes(queue),
// 	                amqp_empty_table);
// 	die_on_amqp_error(amqp_get_rpc_reply(*conn), "Binding queue");
// }

// // bool isPublish()
// // {
// // 	bool publish;
// // 	return publish;
// // }

// // bool isConnected(int ip, const TCP& tcp)
// // {
// // 	// cout << "FOUND AMQP at port " << tcp.sport() << "\n";
// // 	int clientPort = tcp.sport()
// // 	// std::string s = std::to_string(42);
// // 	portMap.insert(pair<int,string>(ip+":"+to_string(clientPort),topic) );
// // }


// Client getClientFromMap()
// {

// 	return client;
// }

// void addClientToMap(int ip, const TCP& tcp)
// {
// 	portMap.insert(pair<int,string>(ip+":"+to_string(clientPort),topic) );
// }

int processAmqpPacket(RawPDU::payload_type payload)
{
	int operations = -1;
	string message( payload.begin(), payload.end() );

	// const char *msg = message.c_str();
	stringstream result;
	result << std::setw(2) << std::setfill('0') << std::hex << std::uppercase;
	std::copy(message.begin(), message.end(), std::ostream_iterator<unsigned int>(result));
	string temp(result.str());
	size_t found;
	// cout << message << endl;

	found = temp.find("0140A");//Connect AMQP
	if(found != string::npos)
	{
		cout << "AMQP New Connection\n";
		operations = 0;
		cout << temp << endl;
	}
	else{
		found = temp.find("0280A");//Exchange Declare
		if(found != string::npos)
		{
			// cout << found << endl;
			cout << "Exchange Declare\n";
			// "746F706963"
			found = found+8;//0280A + 00 == size_value
			int size;
			std::stringstream ss;
			string sizeVal = temp[found-1];
			if (sizeVal == '1')
				sizeVal += temp[found];
			ss << std::hex << sizeVal;
			ss >> size;
			std::cout << static_cast<int>(size) << std::endl;
			// int size = lexical_cast<int>temp[found+3-1];
			string exchange_name (message, found, size);



			// string exchange_name (test.begin(), test.begin()+found);
			// // string test2 (temp, found+3, size);
			// found = test.find("topic");
			// operations = 1;
			// cout << test << endl;
			// cout << test2 << endl;
			// if(found != string::npos)
			// {
			// 	// cout << found << endl;
				
			// 	string exchange_type ("topic");
			// 	cout << exchange_name << endl;
			// }
			// else
			// {
			// 	found = test.find("direct");
			// 	if(found != string::npos)
			// 	{
			// 		string exchange_name (test.begin(), test.begin()+found);
			// 		string exchange_type ("direct");
			// 		cout << exchange_name << endl;
			// 	}
			// }

		}
		else{
			found = temp.find("0320A");//Queue Declare
			if(found != string::npos)
			{
				cout << "Queue Declare\n";
				found = found+5;//0320A
				string test (message, found);
				operations = 2;
				cout << temp << endl;
				cout << test << endl;
				size_t found2 = temp.find("00000");
				if(found2 != string::npos)
				{
					string chars (message, found, found2-found);

					// string queue_name (test.begin(), test.begin()+found);
					cout << message.size() << endl;
					cout << test << endl;
				}
			}
			else{
				found = temp.find("032014");//Queue Bind
				if(found != string::npos)
				{
					cout << "Queue Bind\n";
					found = found+6;//0280A + 3 Bytes
					string test (message, found);
					operations = 3;
					cout << temp << endl;
					cout << test << endl;
				}
				else{
					found = temp.find("03C014");//Basic Consume
					if(found != string::npos)
					{
						cout << "Basic Consume\n";
						found = found+6;//0280A + 3 Bytes
						string test (message, found);
						operations = 4;
						cout << temp << endl;
						cout << test << endl;
					}
				}
			}
		}
	}
	return operations;
}

bool count_packets(PDU &temp) {
	const IP &ip = temp.rfind_pdu<IP>();
	cout << "Src address: " << ip.src_addr() << endl;
	string clientIp = ip.src_addr().to_string();
	if((clientIp == "10.10.0.1") || (clientIp == "10.10.0.2")){
		const TCP &tcp= temp.rfind_pdu<TCP>();
		if(tcp.dport() == 5672){
			const RawPDU &raw = temp.rfind_pdu<RawPDU>();
			const RawPDU::payload_type& payload = raw.payload();
	    	int op = processAmqpPacket(payload);
    		// switch(op)
    		// {
    		// 	case 0:
	    	// 		Client cli = new client();
	    	// 		cli->connPub = amqp_new_connection();
	    	// 		amqp_new_connection(&cli->connPub, "joint_state_sub");
	    	// 		connect_to_broker();
	    	// 		addClientToMap(clientIp, tcp);
	    	// 		break;
	    	// 	case 1:
	    	// 		getClientFromMap();

	    	// 		break;
	    	// 	default:
	    	// 		break;
    		// }

	    	// 	if(isToPublish())
	    	// 	{
	    	// 		Client& cli = getClientFromMap();
	    	// 		PublishRabbitMQ(ros::Time::now().toSec(), &cli->connection, "clock", "/clock");
	    	// 	}
	    	// }
	    }
	}
    return true;
}


int main() {
 	// Sniffer("h3-eth0").sniff_loop(count_packets);
    FileSniffer sniffer("/home/db/floodlight/sniff/trace_45.pcap");
    sniffer.sniff_loop(count_packets);
    // =============================================================
    // // Sniff on the provided interface in promiscuos mode
    // Sniffer sniffer(argv[1], Sniffer::PROMISC);

    // // Only capture udp packets sent to port 53
    // sniffer.set_filter("port 5060");

    // // Start the capture
    // sniffer.sniff_loop(callback);
    // =============================================================

    // std::cout << "There are " << counter << " packets in the pcap file\n";
    // The address to resolve

}












//    // Always keep looping. When the end of the file is found, 
//    // our callback will simply not be called again.
//    IPv4Address to_resolve(ip.dst_addr());
// // The interface we'll use, since we need the sender's HW address
// NetworkInterface iface(to_resolve);
// // The interface's information
// auto info = iface.addresses();
// // Make the request
// const RawPDU &raw = temp.rfind_pdu<RawPDU>();
// EthernetII pkt = EthernetII("00:00:00:00:00:03", "00:00:00:00:00:01")
// 				 / IP("10.10.0.3", "10.10.0.1")
// 				 / TCP(12345, 80) / RawPDU(raw);
// // The sender
// PacketSender sender;
// // Send and receive the response.
// std::unique_ptr<PDU> response(sender.send_recv(pkt, iface));
// // Did we receive anything?
// if (response) {
//     // const ARP &arp = response->rfind_pdu<ARP>();
//     std::cout << "CHEGOU" << std::endl;
// }
// else
// 	std::cout << "Nothing" << std::endl;