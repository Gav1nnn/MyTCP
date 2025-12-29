/***************************2.1: ACK/NACK*****************/
/***** Feng Hong; 2015-12-09******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.TimerTask;

import javax.management.RuntimeErrorException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Receiver extends TCP_Receiver_ADT {
	
	private TCP_PACKET ackPacket;
	private UDT_Timer timer = new UDT_Timer();
	private ReceiverWindow window = new ReceiverWindow(16);
	private int lastAckSeq = 0;
	
	/*构造函数*/
	public TCP_Receiver() {
		super();	//调用超类构造函数
		super.initTCP_Receiver(this);	//初始化TCP接收端
	}

	@Override
	public void rdt_recv(TCP_PACKET recvPack) {
		int dataLenth = recvPack.getTcpS().getData().length;

		// checksum 错：立即回重复 ACK（不推进 base）
		if (CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) {
			// 取消可能存在的延迟 ACK，避免后续发“过时 ACK”
			if (timer != null) timer.cancel();
			timer = new UDT_Timer();
			tcpH.setTh_ack(lastAckSeq);
			TCP_PACKET ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
			tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
			ackPack.setTcpH(tcpH);
			reply(ackPack);
			return;
		}

		// checksum 对：先尝试缓存
		int bufferResult;
		try {
			bufferResult = window.bufferPacket(recvPack.clone());
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException(e);
		}

		// 如果是 base（按序到达），则连续交付并推进 base，然后做 500ms 累积确认
		if (bufferResult == AckFlag.IS_BASE.ordinal()) {
			boolean deliveredAny = false;
			int newestInOrderSeq = lastAckSeq;

			TCP_PACKET p = window.getPacket();
			while (p != null) {
				deliveredAny = true;
				dataQueue.add(p.getTcpS().getData());

				// 这里沿用你现有 ACK 定义：ACK = 最后按序交付包的 th_seq
				newestInOrderSeq = p.getTcpH().getTh_seq();

				p = window.getPacket();
			}

			if (deliveredAny) {
				lastAckSeq = newestInOrderSeq;
			}

			// 重新安排 500ms 的“累计确认”
			if (timer != null) timer.cancel();
			timer = new UDT_Timer();

			final int ackToSend = lastAckSeq;  // 关键：捕获到局部变量，避免 ackPacket 被后续覆盖产生竞态
			final TCP_PACKET delayedAck = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
			tcpH.setTh_ack(ackToSend);
			tcpH.setTh_sum(CheckSum.computeChkSum(delayedAck));
			delayedAck.setTcpH(tcpH);

			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					reply(delayedAck);
				}
			}, 500);

			System.out.println();
			deliver_data();
			return;
		}

		// 不是 base：ORDERED、DUPLICATE、UNORDERED
		// -> 立即回重复 ACK（dupACK），以支持后续 Reno 的 3 dupACK 快速重传
		if (timer != null) timer.cancel();
		timer = new UDT_Timer();

        tcpH.setTh_ack(lastAckSeq);
        TCP_PACKET ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
        tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
        ackPack.setTcpH(tcpH);
        reply(ackPack);

		System.out.println();
		deliver_data();
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