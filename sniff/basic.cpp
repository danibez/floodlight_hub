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
#include "utils.c"

using namespace Tins;
using namespace std;


class Client
{
		int id;
	public:
		string ipAddress="10.10.0.3";
		string user;
		amqp_connection_state_t conn = NULL;
		string exchange_name;
		string exchange_type;
		string queue_name;
		string routing_key;
		int delMode;
		RawPDU::payload_type payload;
		void setId(int);
		int getId() {return id;};
};

void Client::setId(int val)
{
	id = val;
}

map<int, Client> portMap;



// void PublishRabbitMQ(Client* cli)
// {
// 	// std_msgs::Float64 msg;
// 	// msg.data = message;
// 	amqp_basic_properties_t props;
// 	props._flags = AMQP_BASIC_CONTENT_TYPE_FLAG | AMQP_BASIC_DELIVERY_MODE_FLAG;
// 	props.content_type = amqp_cstring_bytes("text/plain");
// 	props.delivery_mode = cli->delMode; /* persistent delivery mode */

// 	// ================================================================================
// 	// namespace ser = ros::serialization;
// 	// size_t serial_size = ros::serialization::serializationLength(msg);
// 	// boost::shared_array<uint8_t> buffer(new uint8_t[serial_size]);

// 	// ser::OStream stream(buffer.get(), serial_size);
// 	// ser::serialize(stream, msg);

// 	// std::string s(buffer.get(), buffer.get()+serial_size);

// 	// amqp_bytes_t data;
// 	// data.len = serial_size;
// 	// data.bytes = (void*)s.data();
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

void PublishRabbitMQ(Client *cli)
{

}


void amqp_connect(Client* cli)
{
	amqp_socket_t *socket = NULL;
	int status;

	socket = amqp_tcp_socket_new(cli->conn);
	if (!socket) {
		die("creating TCP socket");
	}

	status = amqp_socket_open(socket, (cli->ipAddress).c_str(), 5672);
	if (status) {
		die("opening TCP socket");
	}
	cout << "connecting" << endl;
	die_on_amqp_error(amqp_login(cli->conn, "/", 0, 131072, 0, AMQP_SASL_METHOD_PLAIN, "figment", "figment"),
	                "Logging in");
	amqp_channel_open(cli->conn, 1);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Opening channel");
}

void amqp_declare_exchange(Client* cli)
{
	amqp_exchange_declare(cli->conn, 1, amqp_cstring_bytes(cli->exchange_name.c_str()), amqp_cstring_bytes(cli->exchange_type.c_str()),
	                  0, 0, 0, 0, amqp_empty_table);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Declaring exchange");
}

void amqp_declare_queue(Client* cli)
{
	amqp_bytes_t queuename = amqp_cstring_bytes(cli->queue_name.c_str());
	amqp_queue_declare_ok_t *r = amqp_queue_declare(cli->conn, 1, queuename, 0, 0, 0, 0,
	                             amqp_empty_table);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Declaring queue");
	queuename = amqp_bytes_malloc_dup(r->queue);
	if (queuename.bytes == NULL) {
		fprintf(stderr, "Out of memory while copying queue name");
	}
}

void amqp_bind_queue(Client* cli)
{
	amqp_bytes_t queuename = amqp_cstring_bytes(cli->queue_name.c_str());
	amqp_queue_bind(cli->conn, 1, queuename, amqp_cstring_bytes(cli->exchange_name.c_str()), amqp_cstring_bytes(cli->queue_name.c_str()),
	                amqp_empty_table);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Binding queue");
}

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


Client* getClientFromMap(int port)
{
	std::map<int,Client>::iterator it;

	it = portMap.find(port);
	if(it != portMap.end())
		return &it->second;
	return NULL;
	
}

void addClientToMap(int clientPort, Client cli)
{
	portMap.insert(pair<int,Client>(clientPort, cli));
}

int processAmqpPacket(RawPDU::payload_type payload, Client* cli)
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
		// cout << ">>>>>AMQP New Connection\n";
		operations = 0;
		// cout << temp << endl;
	}
	else{
		found = temp.find("0280A");//Exchange Declare
		if(found != string::npos)
		{
			operations = 1;
			int sizeTemp = found + 8;
			found = message.rfind("(");
			found += 6;
			// for (int i = 0; i < message.length(); i++)
			cout << ">>>>>Exchange Declare\n";

			int size;
			std::stringstream ss;
			string sizeVal (temp, sizeTemp-1, 1);
			if (sizeVal.compare("1") == 0)
			{
				sizeTemp++;
				sizeVal += temp[sizeTemp-1];
			}
			ss << std::hex << sizeVal;
			ss >> size;

			string exchange_name (message, found, size);
			cout << exchange_name << ' ' << exchange_name.length() << endl;




			found += size+1;
			sizeTemp += size*2;
			char c = temp[sizeTemp];

			std::stringstream ss2;
			string sizeVal2 (temp, sizeTemp, 1);
			if (sizeVal2.compare("1") == 0)
				sizeVal2 += temp[sizeTemp+1];
			ss2 << std::hex << sizeVal2;
			ss2 >> size;

			string exchange_type(message, found, size);
			found = exchange_name.find("Feedback");
			// if(found != string::npos)
			// {
			// 	for(int i = 0; i < exchange_name.length(); i++)
			// 		cout << exchange_name[i] << endl;
			// }

			// cli->exchange_name = exchange_name;
			// cli->exchange_type = exchange_type;
			cout << exchange_type.c_str() << ' ' << exchange_type.length() << endl;
		}
		else{
			found = temp.find("0320A");//Queue Declare
			if(found != string::npos)
			{
				operations = 2;
				cout << ">>>>>Queue Declare\n";
				int sizeTemp = found + 8;
				found = message.find("2");
				found += 6;

				int size;
				std::stringstream ss;
				string sizeVal (temp, sizeTemp-1, 1);
				if ((sizeVal.compare("1") == 0) || (sizeVal.compare("2") == 0))
				{
					sizeTemp++;
					sizeVal += temp[sizeTemp-1];
				}
				ss << std::hex << sizeVal;
				ss >> size;

				string queue_name (message, found, size);
				// cli->queue_name = queue_name;
				cout << queue_name << endl;
			}
			else{
				found = temp.find("032014");//Queue Bind
				if(found != string::npos)
				{
					operations = 3;
					// cout << ">>>>>Queue Bind\n";
					int sizeTemp = found + 9;
					found = message.find("2");
					found += 6;

					// cout << found << " " << sizeTemp << endl;
					// cout << message << endl;
					// cout << temp << endl;

					int size;
					std::stringstream ss;
					string sizeVal (temp, sizeTemp-1, 1);
					if ((sizeVal.compare("1") == 0) || (sizeVal.compare("2") == 0))
					{
						sizeTemp++;
						sizeVal += temp[sizeTemp-1];
					}
					ss << std::hex << sizeVal;
					ss >> size;

					string queue_name (message, found, size);
					cout << queue_name << endl;



					found += size+1;
					sizeTemp += size*2;
					char c = temp[sizeTemp];

					std::stringstream ss2;
					string sizeVal2 (temp, sizeTemp, 1);
					if ((sizeVal2.compare("1") == 0))// || (sizeVal2.compare("2") == 0))
					{
						sizeTemp++;
						sizeVal2 += temp[sizeTemp-1];
					}
					ss2 << std::hex << sizeVal2;
					ss2 >> size;
					// cout << c << endl; 

					string exchange_name(message, found, size);
					// cout << exchange_name << endl;



					found += size+1;
					sizeTemp += (size*2)+1;
					c = temp[sizeTemp];
					// cout << c << ' ' << temp[sizeTemp+1] << endl;

					std::stringstream ss3;
					string sizeVal3 (temp, sizeTemp, 1);
					if ((sizeVal3.compare("1") == 0) || (sizeVal3.compare("2") == 0))
					{
						sizeTemp++;
						sizeVal3 += temp[sizeTemp];
					}
					ss3 << std::hex << sizeVal3;
					ss3 >> size;
					// cout << c << " " << sizeVal3 << ' ' << size << endl; 

					string routing_key(message, found, size);
					// cli->routing_key = routing_key;
					cout << routing_key << endl;
				}
				else{
					found = temp.find("706C61");//publish
					if(found != string::npos)
					{
						// int sizeTemp = found + 10;
						// int delivery_mode = temp[sizeTemp]-'0';
						// // cout << "delMode: " << delivery_mode << endl; 
						// found = message.find("plain");
						// sizeTemp += 18;

						// int size;
						// std::stringstream ss;
						// string sizeVal (temp, sizeTemp, 1);
						// if ((sizeVal.compare("0") == 0))// || (sizeVal.compare("2") == 0))
						// {
						// 	sizeTemp++;
						// 	sizeVal += temp[sizeTemp-1];
						// }
						// ss << std::hex << sizeVal;
						// ss >> size;
						// cout << sizeVal << ' ' << size << endl;
						operations = 4;
						cli->payload = temp;
						// cli->publish();
						// cout << "=============================\n" << message << "\n=============================\n" << endl;
					}
				}
			}
		}
	}
	return operations;
}

bool count_packets(PDU &temp) {
	const IP &ip = temp.rfind_pdu<IP>();
	// cout << "Src address: " << ip.src_addr() << endl;
	string clientIp = ip.src_addr().to_string();
	if((clientIp == "10.10.0.1") || (clientIp == "10.10.0.2")){
		const TCP &tcp= temp.rfind_pdu<TCP>();
		if(tcp.dport() == 5672){
			const RawPDU &raw = temp.rfind_pdu<RawPDU>();
			const RawPDU::payload_type& payload = raw.payload();
			Client* cli = getClientFromMap(tcp.sport());
	    	int op = processAmqpPacket(payload, cli);
	    	// if(op >= 0)
    		// cout << op << endl;
    		// switch(op)
    		// {
    		// 	case 0:
	    	// 		cli = new Client();
	    	// 		cli->setId(tcp.sport());
	    	// 		cli->conn = amqp_new_connection();
	    	// 		amqp_connect(cli);
	    	// 		addClientToMap(tcp.sport(), *cli);
	    	// 		break;
	    	// 	case 1:
	    	// 		cli = getClientFromMap(tcp.sport());
	    	// 		amqp_declare_exchange(cli);//, bool declare_queue)
	    	// 		break;
    		// 	case 2:
	    	// 		cli = getClientFromMap(tcp.sport());
	    	// 		amqp_declare_queue(cli);//, bool declare_queue)
	    	// 		break;
	    	// 	case 3:
	    	// 		cli = getClientFromMap(tcp.sport());
	    	// 		amqp_bind_queue(cli);//, bool declare_queue)
	    	// 		break;
	    	// 	case 4:
	    	// 		// cli = getClientFromMap(tcp.sport());
	    	// 		// amqp_bind_queue(cli);//, bool declare_queue)
	    	// 		// break;
	    	// 	default:
	    	// 		break;
    		// }

	    		// if(isToPublish())
	    		// {
	    		// 	Client& cli = getClientFromMap();
	    		// 	PublishRabbitMQ(ros::Time::now().toSec(), &cli->connection, "clock", "/clock");
	    		// }

	    }
	}
    return true;
}


int main() {
 	// Sniffer("h3-eth0").sniff_loop(count_packets);
    FileSniffer sniffer("/home/db/floodlight/sniff/trace.pcap");
    sniffer.sniff_loop(count_packets);
    cout << "press Q to exit";
    char isExit;
    while(1)
    {
    	cin >> isExit;
    	if(isExit == 'q')
    		break;
    }
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