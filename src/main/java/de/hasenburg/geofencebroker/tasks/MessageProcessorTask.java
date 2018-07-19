package de.hasenburg.geofencebroker.tasks;

import de.hasenburg.geofencebroker.communication.RouterCommunicator;
import de.hasenburg.geofencebroker.main.Utility;
import de.hasenburg.geofencebroker.model.RouterMessage;
import de.hasenburg.geofencebroker.model.connections.ConnectionManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zeromq.ZMsg;
import zmq.socket.reqrep.Router;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A Task that continuously processes messages.
 * 
 * @author jonathanhasenburg
 *
 */
class MessageProcessorTask extends Task<Boolean> {

	private static final Logger logger = LogManager.getLogger();

	BlockingQueue<ZMsg> messageQueue;
	RouterCommunicator routerCommunicator;
	ConnectionManager connectionManager;

	protected MessageProcessorTask(TaskManager taskManager, BlockingQueue<ZMsg> messageQueue, RouterCommunicator routerCommunicator, ConnectionManager connectionManager) {
		super(TaskManager.TaskName.MESSAGE_PROCESSOR_TASK, taskManager);
		this.messageQueue = messageQueue;
		this.routerCommunicator = routerCommunicator;
		this.connectionManager = connectionManager;
	}

	@Override
	public Boolean executeFunctionality() {

		int queuedMessages = 0;

		while (!Thread.currentThread().isInterrupted()) {
			if (queuedMessages != messageQueue.size()) {
				logger.trace("Number of queued messages: " + messageQueue.size());
				queuedMessages = messageQueue.size();
			}

			Optional<RouterMessage> messageO = RouterMessage.buildRouterMessage(messageQueue.poll());

			messageO.ifPresent(message -> {
				switch (message.getControlPacketType()) {
					case CONNECT:
						connectionManager.processCONNECT(message);
						break;
					case DISCONNECT:
						connectionManager.processDISCONNECT(message);
						break;
					default:
						logger.debug("Cannot process message {}", message.toString());
				}
			});

			Utility.sleepNoLog(0, 10); // when interrupted, the task will end which is fine
		}

		return true;
	}

}