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
#include <cstdlib>
#include <iostream>
#include "utils.c"

#include <algorithm>
#include <stdexcept>

using namespace Tins;
using namespace std;

#define VERBOSE 0
#define TEST 0


class Client
{
		int id;
	public:
		string ipAddress="10.10.0.4";
		string user;
		amqp_connection_state_t conn = NULL;
		string exchange_name;
		string exchange_type;
		string queue_name;
		string bQueue_name;
		string bExchange_name;
		string routing_key;
		string payload;
		amqp_bytes_t qMalloc;
		int delMode;
		void setId(int);
		int getId() {return id;};
};

void Client::setId(int val)
{
	id = val;
}

map<int, Client> portMap;

int counts=0;

void amqp_publish(Client *cli)
{
	if(VERBOSE)
		cout << "Publishing\n";
	
	amqp_basic_properties_t props;
	props._flags = AMQP_BASIC_CONTENT_TYPE_FLAG | AMQP_BASIC_DELIVERY_MODE_FLAG;
	props.content_type = amqp_cstring_bytes("text/plain");
	props.delivery_mode = cli->delMode; /* persistent delivery mode */

	// size_t serial_size = cli->payload.length();
	// boost::shared_array<uint8_t> buffer(new uint8_t[serial_size]);
	
	// ser::OStream stream(buffer.get(), serial_size);
	// ser::serialize(stream, msg);
	
	// std::string s(buffer.get(), buffer.get()+serial_size);

	// std::vector<uint8_t> myVector(cli->payload.begin(), cli->payload.end());
	// uint8_t *p = &myVector[0];

	amqp_bytes_t data;
	data.len = cli->payload.length();
	data.bytes = (void*)cli->payload.data();
	// const char* b = cli->payload;
	// if(cli->exchange_name.compare("clock") == 0){
	// 	cout << cli->exchange_name << ' ' << cli->payload.data() << endl;
	// }
	die_on_error(amqp_basic_publish(cli->conn,
									1,
									amqp_cstring_bytes(cli->exchange_name.c_str()),
									amqp_cstring_bytes(cli->queue_name.c_str()),
									0,
									0,
									&props,
									data),
				"Publishing");
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
	if(VERBOSE)
		cout << "connecting" << endl;
	
	die_on_amqp_error(amqp_login(cli->conn, "/", 0, 131072, 0, AMQP_SASL_METHOD_PLAIN, "figment", "figment"),
	                "Logging in");
	amqp_channel_open(cli->conn, 1);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Opening channel");
}

void amqp_declare_exchange(Client* cli)
{
	const char* ex = cli->exchange_name.c_str();
	const char* exT = cli->exchange_type.c_str();
	amqp_exchange_declare(cli->conn, 1, amqp_cstring_bytes(ex), amqp_cstring_bytes(exT),
	                  0, 0, 0, 0, amqp_empty_table);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Declaring exchange");
}

void amqp_declare_queue(Client* cli)
{
	const char* q = cli->queue_name.c_str();
	amqp_bytes_t queuename = amqp_cstring_bytes(q);
	amqp_queue_declare_ok_t *r = amqp_queue_declare(cli->conn, 1, queuename, 0, 0, 0, 0,
	                             amqp_empty_table);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Declaring queue");
	queuename = amqp_bytes_malloc_dup(r->queue);
	if (queuename.bytes == NULL) {
		fprintf(stderr, "Out of memory while copying queue name");
	}
	cli->qMalloc = queuename;
}

void amqp_bind_queue(Client* cli)
{
	// amqp_bytes_t queuename = amqp_cstring_bytes(cli->queue_name.c_str());
	const char* ex = cli->bExchange_name.c_str();
	const char* q = cli->bQueue_name.c_str();
	amqp_queue_bind(cli->conn, 1, cli->qMalloc, amqp_cstring_bytes(ex), amqp_cstring_bytes(q),
	                amqp_empty_table);
	die_on_amqp_error(amqp_get_rpc_reply(cli->conn), "Binding queue");
}

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

std::string hex_to_string(const std::string& input)
{
    static const char* const lut = "0123456789abcdef";
    size_t len = input.length();
    if (len & 1) throw std::invalid_argument("odd length");

    std::string output;
    output.reserve(len / 2);
    for (size_t i = 0; i < len; i += 2)
    {
        char a = input[i];
        const char* p = std::lower_bound(lut, lut + 16, a);
        if (*p != a) throw std::invalid_argument("not a hex digit");

        char b = input[i + 1];
        const char* q = std::lower_bound(lut, lut + 16, b);
        if (*q != b) throw std::invalid_argument("not a hex digit");

        output.push_back(((p - lut) << 4) | (q - lut));
    }
    return output;
}

char* hex_to_char(const std::string& input)
{
    static const char* const lut = "0123456789abcdef";
    size_t len = input.length();
    int k=0;
    if (len & 1) throw std::invalid_argument("odd length");

    char* output = new char[(len/2)+1];
    for (size_t i = 0; i < len; i += 2)
    {
        char a = input[i];
        const char* p = std::lower_bound(lut, lut + 16, a);
        if (*p != a) throw std::invalid_argument("not a hex digit");

        char b = input[i + 1];
        const char* q = std::lower_bound(lut, lut + 16, b);
        if (*q != b) throw std::invalid_argument("not a hex digit");

        output[k] = ((p - lut) << 4) | (q - lut);
        k++;
    }
    output[k] = '\0';
    return output;
}

int processAmqpPacket(RawPDU::payload_type payload, Client* cli)
{
	int operations = -1;
	string message( payload.begin(), payload.end() );

	stringstream ss;
	ss << std::hex;
    for(int i=0;i<payload.size();++i)
        ss << std::setw(2) << std::setfill('0') << (unsigned int)payload[i];
    // cout << ss.str() << endl;

	// const char *msg = message.c_str();
	// stringstream result;
	// result << std::hex << std::uppercase;
	// std::copy(payload.begin(), payload.end(), std::ostream_iterator<unsigned int>(result));
	// unsigned int x = std::strtoul(message.c_str(), nullptr, 16);
	// cout << x << endl;
	string temp(ss.str());
	size_t found;
	// cout << "AQUI" << message << endl;

	found = temp.find("0014000a");//Connect AMQP
	if(found != string::npos)
	{
		if(VERBOSE)
			cout << ">>>>>AMQP New Connection\n";
		operations = 0;
		// cout << temp << endl;
	}
	else{
		found = temp.find("0028000a");//Exchange Declare
		if(found != string::npos)
		{
			operations = 1;
			int sizeTemp = found + 14;
			if(VERBOSE)
				cout << ">>>>>Exchange Declare\n";
			

			int size;
			std::stringstream ss;
			string sizeVal (temp, sizeTemp-2, 2);
			ss << std::hex << sizeVal;
			ss >> size;
			size *= 2;

			string ex (temp, sizeTemp, size);
			string exchange_name = hex_to_string(ex);

			sizeTemp += size+2;


			if(VERBOSE)
				cout << exchange_name << ' ' << exchange_name.length() << endl;
			
			std::stringstream ss2;
			string sizeVal2 (temp, sizeTemp-2, 2);
			ss2 << std::hex << sizeVal2;
			ss2 >> size;
			size *= 2;

			string type (temp, sizeTemp, size);
			string exchange_type = hex_to_string(type);

			if(!TEST){
				cli->exchange_name = exchange_name;
				cli->exchange_type = exchange_type;
			}
			if(VERBOSE)
				cout << exchange_type.c_str() << ' ' << exchange_type.length() << endl;
			
		}
		else{
			found = temp.find("0032000a");//Queue Declare
			if(found != string::npos)
			{
				operations = 2;
				if(VERBOSE)
					cout << ">>>>>Queue Declare\n";
				
				int sizeTemp = found + 14;


				int size;
				std::stringstream ss;
				string sizeVal (temp, sizeTemp-2, 2);
				ss << std::hex << sizeVal;
				ss >> size;
				size *= 2;

				string q (temp, sizeTemp, size);
				string queue_name = hex_to_string(q);

				if(!TEST)
					cli->queue_name = queue_name;
				if(VERBOSE)
					cout << queue_name << endl;
				
			}
			else{
				found = temp.find("00320014");//Queue Bind
				if(found != string::npos)
				{
					operations = 3;
					if(VERBOSE)
						cout << ">>>>>Queue Bind\n";
					
					int sizeTemp = found + 14;

					int size;
					std::stringstream ss;
					string sizeVal (temp, sizeTemp-2, 2);
					ss << std::hex << sizeVal;
					ss >> size;
					size *= 2;

					string q (temp, sizeTemp, size);
					string queue_name = hex_to_string(q);

					sizeTemp += size+2;


					if(VERBOSE)
						cout << queue_name << ' ' << queue_name.length() << endl;
					
					std::stringstream ss2;
					string sizeVal2 (temp, sizeTemp-2, 2);
					ss2 << std::hex << sizeVal2;
					ss2 >> size;
					size *= 2;

					string ex (temp, sizeTemp, size);
					string exchange_name = hex_to_string(ex);

					if(VERBOSE)
						cout << exchange_name << ' ' << exchange_name.length() << endl;
					
					sizeTemp += size+2;

					std::stringstream ss3;
					string sizeVal3 (temp, sizeTemp-2, 2);
					ss3 << std::hex << sizeVal3;
					ss3 >> size;
					size *= 2;

					string rk (temp, sizeTemp, size);
					string routing_key = hex_to_string(rk);

					if(VERBOSE)
						cout << routing_key << ' ' << routing_key.length() << endl;
					

					if(!TEST){
						cli->bQueue_name = queue_name;
						cli->bExchange_name = exchange_name;
						cli->routing_key = routing_key;
					}
					if(VERBOSE)
						cout << routing_key << endl;
					
				}
				else{
					size_t found;
					found = temp.find("030001");
					if(found != string::npos)
					{
						operations = 4;
						if(VERBOSE)
							cout << ">>>>>Publish\n";

						int sizeTemp = found + 6;
						// cout << temp << endl;
						if(found >= 4){
							string delMode (temp, found-4, 2);
							int mode;
							std::stringstream ssMode;
							ssMode << std::hex << delMode;
							ssMode >> mode;

							cli->delMode = mode;
						}
						else
							cli->delMode = 2;

						
						// cout << temp;
						string payload_size (temp, sizeTemp, 8);

						int size;
						std::stringstream ss;
						ss << std::hex << payload_size;
						ss >> size;
						size *= 2;

						sizeTemp += 8;

						string data (temp, sizeTemp, size);
						// string payload_data = hex_to_string(data);
						cli->payload = hex_to_string(data);
						// cout << payload_data;


						

						// cout << p << endl;

						if(!TEST)
						{
							// 	char * copy = malloc(strlen(original) + 1); 
							// 	strcpy(copy, original);
							// cli->payload = payload_data;
						}
					}
					else
					{
						// cout << "No Match: " << counts << endl ;
						operations = -1;
						// cout << temp << endl;
					}
					// cout << counts << endl;
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
	counts++;
	if((clientIp == "10.10.0.1") || (clientIp == "10.10.0.2")){
		const TCP &tcp= temp.rfind_pdu<TCP>();
		if(tcp.dport() == 5672){
			const RawPDU &raw = temp.rfind_pdu<RawPDU>();
			const RawPDU::payload_type& payload = raw.payload();
			Client* cli = getClientFromMap(tcp.sport());
	    	int op = processAmqpPacket(payload, cli);

    		if(!TEST){
	    		switch(op)
	    		{
	    			case 0:
		    			cli = new Client();
		    			cli->setId(tcp.sport());
		    			cli->conn = amqp_new_connection();
		    			if(ip.dst_addr().to_string().compare("10.10.0.3") == 0)
		    				cli->ipAddress = "10.10.0.4";
		    			else
		    				cli->ipAddress = "10.10.0.3";
		    			amqp_connect(cli);
		    			addClientToMap(tcp.sport(), *cli);
		    			break;
		    		case 1:
		    			cli = getClientFromMap(tcp.sport());
		    			amqp_declare_exchange(cli);//, bool declare_queue)
		    			break;
	    			case 2:
		    			cli = getClientFromMap(tcp.sport());
		    			amqp_declare_queue(cli);//, bool declare_queue)
		    			break;
		    		case 3:
		    			cli = getClientFromMap(tcp.sport());
		    			amqp_bind_queue(cli);//, bool declare_queue)
		    			break;
		    		case 4:
		    			cli = getClientFromMap(tcp.sport());
		    			amqp_publish(cli);//, bool declare_queue)
		    			break;
		    		default:
		    			break;
	    		}
	    	}
	    }
	}
    return true;
}


int main(int argc, char* argv[]) {
	if (argc != 2) {
        // Tell the user how to run the program
        std::cerr << "Just pass the Interface argument!" << std::endl;
        /* "Usage messages" are a conventional way of telling the user
         * how to run a program if they enter the command incorrectly.
         */
        return 1;
    }
    const char* interface = argv[1];
 	if(!TEST)
 		Sniffer(interface).sniff_loop(count_packets);
 	else{
		FileSniffer sniffer("/home/db/floodlight/sniff/traceh3.pcap");
		sniffer.sniff_loop(count_packets);
    }
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