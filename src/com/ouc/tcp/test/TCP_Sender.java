/***************************2.1: ACK/NACK
**************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import javax.management.RuntimeErrorException;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	private volatile int flag = WindowFlag.NOT_FULL.ordinal();
	// 引入窗口大小
	private final SenderWindow window = new SenderWindow(16);

	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
	}

	// 加入计时器
	private UDT_Timer timer;

	// 新增方法启动定时器
	private void startTimer(){
		timer = new UDT_Timer();
		timer.schedule(
			new UDT_RetransTask(client, tcpPack), 
			// 3s后开始重传，每3s重传一次
			3000,
			3000
		);
	}

	// 新增方法停用定时器
	private void stopTimer(){
		if (timer != null){
			timer.cancel();
			timer = null;
		}
	}
	
	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报；需要修改
	public void rdt_send(int dataIndex, int[] appData) {

		
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		// 1.设置序号
		tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号：
		// 2.封装数据
		tcpS.setData(appData);
		tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);		
		// 3.计算校验和		
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		// 4.检查窗口是否已满
		if(window.isFull()){
			flag = WindowFlag.FULL.ordinal();
		}
		// 如果满，阻塞等待窗口滑动
		while (flag == WindowFlag.FULL.ordinal()) {
			
		}

		// 5.将包加入窗口并发送
		try {
			window.pushPacket(tcpPack.clone());
		} catch(CloneNotSupportedException e){
			throw new RuntimeException(e);
		}

		// 发送并启动这个包的计时器
		window.sendPacket(this, client, 3000, 3000);
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；仅需修改错误标志
	public void udt_send(TCP_PACKET stcpPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)7);		
		//System.out.println("to send: "+stcpPack.getTcpH().getTh_seq());				
		//发送数据报
		client.send(stcpPack);
	}
	
	@Override
	//需要修改
	public void waitACK() {
		// 检查ACK队列
		if (!ackQueue.isEmpty()){
			int currentAck = ackQueue.poll();
			window.ackPacket(currentAck);

			// 如果窗口不满，解除阻塞
			if (!window.isFull()){
				flag = WindowFlag.NOT_FULL.ordinal();
			}

		}
	}

	@Override
	//接收到ACK报文：检查校验和，将确认号插入ack队列;NACK的确认号为－1；不需要修改
	public void recv(TCP_PACKET recvPack) {
		System.out.println("Receive ACK Number： "+ recvPack.getTcpH().getTh_ack());
		ackQueue.add(recvPack.getTcpH().getTh_ack());
	    System.out.println();	
	    //处理ACK报文
	    waitACK();

	}
	
}
