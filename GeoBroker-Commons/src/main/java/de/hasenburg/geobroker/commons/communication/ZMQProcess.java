package de.hasenburg.geobroker.commons.communication;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMsg;

import java.util.List;

/**
 * ZMQProcesses are submitted to the ZMQProcessManager by using {@link ZMQProcessManager#submitZMQProcess(String,
 * ZMQProcess)}.
 */
public abstract class ZMQProcess implements Runnable {

	private static final Logger logger = LogManager.getLogger();
	private static final int TIMEOUT_SECONDS = 10; // logs when not received in time, but repeats

	private ZContext context = null;

	protected final String identity;
	protected List<Socket> sockets;

	public ZMQProcess(String identity) {
		this.identity = identity;
	}

	// can't be in constructor as added by ZMQProcessManager
	void init(ZContext context) {
		this.context = context;
	}

	@Override
	public void run() {
		// check whether init was called
		if (context == null) {
			logger.fatal("ZMQProcess with identity {} started before init was called, shutting down", identity);
			System.exit(1);
		}

		// set thread name
		Thread.currentThread().setName(identity);

		// get other sockets (UDF)
		sockets = bindAndConnectSockets(context);

		// register them at poller
		ZMQ.Poller poller = context.createPoller(sockets.size() + 1); // +1 as we'll add the control later
		sockets.forEach(s -> poller.register(s, ZMQ.Poller.POLLIN)); // add sockets at poller index 0 to sockets.size()

		// add control socket
		int zmqControlIndex = ZMQControlUtility.connectWithPoller(context, poller, identity);

		// poll all sockets
		while (!Thread.currentThread().isInterrupted()) {
			logger.trace("Waiting {}s for a message", TIMEOUT_SECONDS);
			poller.poll(TIMEOUT_SECONDS * 1000);

			if (poller.pollin(zmqControlIndex)) {
				Pair<ZMQControlUtility.ZMQControlCommand, ZMsg> pair =
						ZMQControlUtility.getCommandAndMsg(poller, zmqControlIndex);
				if (ZMQControlUtility.ZMQControlCommand.KILL.equals(pair.getLeft())) {
					break; // break out of while loop, time to shut down
				} else {
					processZMQControlCommandOtherThanKill(pair.getLeft(), pair.getRight());
				}
			} else {
				// poll each socket
				for (int socketIndex = 0; socketIndex < sockets.size(); socketIndex++) {
					if (poller.pollin(socketIndex)) {
						ZMsg msg = ZMsg.recvMsg(sockets.get(socketIndex));

						// process the ZMsg (UDF)
						processZMsg(socketIndex, msg);

						// do not poll other sockets when we got a message, restart while loop
						break;
					}
				}
			}
		}

		// destroy sockets as we are shutting down
		sockets.forEach(s -> context.destroySocket(s));
		context.destroySocket(poller.getSocket(zmqControlIndex));

		// UDF for shutdown completed
		shutdownCompleted();
	}

	protected abstract List<Socket> bindAndConnectSockets(ZContext context);

	protected abstract void processZMQControlCommandOtherThanKill(ZMQControlUtility.ZMQControlCommand zmqControlCommand,
																  ZMsg msg);

	protected abstract void processZMsg(int socketIndex, ZMsg msg);

	protected abstract void shutdownCompleted();
}
