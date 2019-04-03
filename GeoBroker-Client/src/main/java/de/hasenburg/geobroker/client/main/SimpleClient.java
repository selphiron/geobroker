package de.hasenburg.geobroker.client.main;

import de.hasenburg.geobroker.commons.Utility;
import de.hasenburg.geobroker.commons.communication.ZMQProcessManager;
import de.hasenburg.geobroker.commons.model.message.ControlPacketType;
import de.hasenburg.geobroker.commons.model.message.ReasonCode;
import de.hasenburg.geobroker.client.communication.ZMQProcessStarter;
import de.hasenburg.geobroker.client.communication.ZMQProcess_SimpleClient;
import de.hasenburg.geobroker.client.communication.InternalClientMessage;
import de.hasenburg.geobroker.commons.model.message.payloads.CONNECTPayload;
import de.hasenburg.geobroker.commons.model.message.payloads.DISCONNECTPayload;
import de.hasenburg.geobroker.commons.model.spatial.Location;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

import java.util.Optional;
import java.util.Random;

public class SimpleClient {

	private static final Logger logger = LogManager.getLogger();

	private ZMQProcessManager processManager;
	private String identifier;
	ZMQ.Socket orderSocket;


	public SimpleClient(@Nullable String identifier, String address, int port, ZMQProcessManager processManager) {
		if (identifier == null) {
			Random random = new Random();
			identifier = "SimpleClient-" + System.nanoTime();
		}

		this.identifier = identifier;
		this.processManager = processManager;
		ZMQProcessStarter.runZMQProcess_SimpleClient(processManager, address, port, identifier);
		orderSocket = processManager.getContext().createSocket(SocketType.REQ);
		orderSocket.setIdentity(identifier.getBytes());
		orderSocket.connect(Utility.generateClientOrderBackendString(identifier));

		logger.info("Created client {}", identifier);
	}

	public String getIdentity() {
		return identifier;
	}

	public void tearDownClient() {
		orderSocket.setLinger(0);
		processManager.getContext().destroySocket(orderSocket);
		processManager.sendKillCommandToZMQProcess(getIdentity());
	}

	public ZMsg sendInternalClientMessage(InternalClientMessage message) {
		ZMsg orderMessage = ZMsg.newStringMsg(ZMQProcess_SimpleClient.ORDERS.SEND.name());
		ZMsg internalClientMessage = message.getZMsg();
		for (int i = 0; i <= internalClientMessage.size(); i++) {
			orderMessage.add(internalClientMessage.pop());
		}

		orderMessage.send(orderSocket);
		return(ZMsg.recvMsg(orderSocket));
	}

	public InternalClientMessage receiveInternalClientMessage() {
		ZMsg orderMessage = ZMsg.newStringMsg(ZMQProcess_SimpleClient.ORDERS.RECEIVE.name());

		// send order
		orderMessage.send(orderSocket);
		final Optional<InternalClientMessage> clientMessageO =
				InternalClientMessage.buildMessage(ZMsg.recvMsg(orderSocket));

		return clientMessageO.orElse(null);
	}

	public static void main (String[] args) {
		ZMQProcessManager processManager = new ZMQProcessManager();
	    SimpleClient client = new SimpleClient(null, "tcp://localhost", 5559, processManager);

	    // connect
		InternalClientMessage clientMessage = new InternalClientMessage(ControlPacketType.CONNECT, new CONNECTPayload(
				Location.random()));
		client.sendInternalClientMessage(clientMessage);

		// receive one message
		InternalClientMessage response = client.receiveInternalClientMessage();
		logger.info("Received server answer: {}", response);

		// disconnect
		clientMessage = new InternalClientMessage(ControlPacketType.DISCONNECT, new DISCONNECTPayload(
				ReasonCode.NormalDisconnection));
		client.sendInternalClientMessage(clientMessage);

		client.tearDownClient();
		if (processManager.tearDown(3000)) {
			logger.info("SimpleClient shut down properly.");
		} else {
			logger.fatal("ProcessManager reported that processes are still running: {}", processManager.getIncompleteZMQProcesses());
		}
		System.exit(0);
	}



}