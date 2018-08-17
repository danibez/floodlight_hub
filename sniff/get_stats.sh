#!/bin/bash

quanti = $(ls *.pcap | wc -l)
tcpdump	-i $1-eth0 -w $1/rabbit_$quanti.pcap &

while true; do
	 rabbitmqctl status | grep memory, -A 21 >> memory_$1.txt


