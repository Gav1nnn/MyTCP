/***************************2.1: ACK/NACK*****************/
/***** Feng Hong; 2015-12-09******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Receiver extends TCP_Receiver_ADT {
	
	// private TCP_PACKET ackPack;	//回复的ACK报文段
	// // RDT2.1/2.2，序列号sequence0/1交替
	// int sequence=0;//用于记录当前待接收的包序号，注意包序号不完全是
	private TCP_PACKET ackPacket;
	private ReceiverWindow window = new ReceiverWindow(16);
	
	/*构造函数*/
	public TCP_Receiver() {
		super();	//调用超类构造函数
		super.initTCP_Receiver(this);	//初始化TCP接收端
	}

	@Override
	//接收到数据报：检查校验和，设置回复的ACK报文段
	public void rdt_recv(TCP_PACKET recvPack) {
		int dataLength = recvPack.getTcpS().getData().length;
		// 检查校验和
		if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()){
			// 尝试放入缓存窗口
			int bufferResult = window.bufferPacket(recvPack);

			// 如果是有效包则都需要恢复ACK（有序、重复、基序号）
			if (bufferResult==AckFlag.ORDERED.ordinal() || bufferResult==AckFlag.DUPLICATE.ordinal() || bufferResult==AckFlag.IS_BASE.ordinal()){
				// 设置ACK报文
				tcpH.setTh_ack(recvPack.getTcpH().getTh_seq());
				ackPacket = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPacket));

				// 发送ACK
				reply(ackPacket);
			}

			// 如果是可以交付的包，就连续交付
			if (bufferResult == AckFlag.IS_BASE.ordinal()){
				TCP_PACKET packet = window.getPacket();
				while (packet != null) {
					dataQueue.add(packet.getTcpS().getData());
					packet = window.getPacket();
				}

			}

			System.out.println();

			// 交付数据
			deliver_data();
		}
	}

	@Override
	//交付数据（将数据写入文件）；不需要修改
	public void deliver_data() {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;
		
		try {
			writer = new BufferedWriter(new FileWriter(fw, true));
			
			//循环检查data队列中是否有新交付数据
			while(!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();
				
				//将数据写入文件
				for(int i = 0; i < data.length; i++) {
					writer.write(data[i] + "\n");
				}
				
				writer.flush();		//清空输出缓存
			}
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	//回复ACK报文段
	public void reply(TCP_PACKET replyPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)7);	//eFlag=0，信道无错误
				
		//发送数据报
		client.send(replyPack);
	}
	
}