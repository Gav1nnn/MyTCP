package com.ouc.tcp.test;

import java.util.zip.CRC32;

import com.ouc.tcp.message.TCP_HEADER;
import com.ouc.tcp.message.TCP_PACKET;
import com.ouc.tcp.message.TCP_SEGMENT;

public class CheckSum {
	
	/*计算TCP报文段校验和：只需校验TCP首部中的seq、ack和sum，以及TCP数据字段*/
	/*  RDT2.0假设信道是不可靠的，即数据包可能会出现位错的情况
		故，RDT2.0需要在发送端和接收端实现数据包检验功能 */
	public static short computeChkSum(TCP_PACKET tcpPack) {
		TCP_HEADER tcpHeader = tcpPack.getTcpH();
		int seq = tcpHeader.getTh_seq();
		int ack = tcpHeader.getTh_ack();
		TCP_SEGMENT tcp_SEGMENT = tcpPack.getTcpS();
		int[] data = tcp_SEGMENT.getData();

		CRC32 crc32 = new CRC32();
		crc32.update(seq);
		crc32.update(ack);
		for(int i:data){
			crc32.update(i);
		}
		int checkSum = (int)crc32.getValue();

		// int checkSum = 0;
		return (short) checkSum;
	}
	
}
