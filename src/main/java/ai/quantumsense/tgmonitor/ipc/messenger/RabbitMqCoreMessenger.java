package ai.quantumsense.tgmonitor.ipc.messenger;

import ai.quantumsense.tgmonitor.ipc.CoreMessenger;
import ai.quantumsense.tgmonitor.ipc.payload.Request;
import ai.quantumsense.tgmonitor.ipc.payload.Response;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;

import static ai.quantumsense.tgmonitor.ipc.messenger.Shared.KEY_LOGIN_CODE_REQUEST_QUEUE;
import static ai.quantumsense.tgmonitor.ipc.messenger.Shared.REQUEST_QUEUE;

public class RabbitMqCoreMessenger implements CoreMessenger {

    private Logger logger = LoggerFactory.getLogger(RabbitMqCoreMessenger.class);

    private Connection connection;
    private Channel channel;
    private Serializer serializer;

    private String loginCodeRequestQueue;

    public RabbitMqCoreMessenger(Serializer serializer) {
        this.serializer = serializer;
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("localhost");
            logger.debug("Connecting to RabbitMQ on " + factory.getHost());
            connection = factory.newConnection();
            channel = connection.createChannel();
            logger.debug("Declaring request queue \"" + REQUEST_QUEUE + "\"");
            channel.queueDeclare(REQUEST_QUEUE, false, false, true, null);
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void startRequestListener(OnRequestReceivedCallback callback) {
        try {
            logger.debug("Start listening for requests on queue \"" + REQUEST_QUEUE + "\"");
            channel.basicConsume(REQUEST_QUEUE, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties requestProps, byte[] body) {
                    Request request = serializer.deserializeRequest(body);
                    logger.debug("Received request " + request + " on queue \"" + REQUEST_QUEUE + "\" with correlation ID " + requestProps.getCorrelationId());
                    // If this is a login request, get the queue to use for the login code request
                    if (requestProps.getHeaders() != null && requestProps.getHeaders().containsKey(KEY_LOGIN_CODE_REQUEST_QUEUE)) {
                        loginCodeRequestQueue = requestProps.getHeaders().get(KEY_LOGIN_CODE_REQUEST_QUEUE).toString();
                        logger.debug("This is a login request, retrieved queue for login code request from message header: " + loginCodeRequestQueue);
                    }
                    Response response = callback.onRequestReceived(request);
                    try {
                        String responseQueue = requestProps.getReplyTo();
                        AMQP.BasicProperties responseProps = new AMQP.BasicProperties.Builder()
                                .correlationId(requestProps.getCorrelationId())
                                .build();
                        logger.debug("Sending back response " + response + " on queue \"" + responseQueue + "\" with correlation ID " + requestProps.getCorrelationId());
                        channel.basicPublish("", responseQueue, responseProps, serializer.serialize(response));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Response loginCodeRequest(Request request) {
        final BlockingQueue<byte[]> wait = new ArrayBlockingQueue<>(1);
        try {
            String correlationId = makeUuid();
            String replyToQueue = "login_code_response";
            channel.queueDeclare(replyToQueue, false, false, false, null);
            AMQP.BasicProperties requestProps = new AMQP.BasicProperties
                    .Builder()
                    .correlationId(correlationId)
                    .replyTo(replyToQueue)
                    .build();
            logger.debug("Sending login code request " + request + " on queue \"" + loginCodeRequestQueue + " with correlation ID " + correlationId);
            channel.basicPublish("", loginCodeRequestQueue, requestProps, serializer.serialize(request));
            logger.debug("Start listening for response to login code request on queue \"" + replyToQueue + "\"");
            channel.basicQos(10);
            String consumerTag = channel.basicConsume(replyToQueue, false, new Consumer() {
                @Override
                public void handleConsumeOk(String consumerTag) {
                    logger.debug("handleConsumeOk");
                }

                @Override
                public void handleCancelOk(String consumerTag) {
                    logger.debug("handleCancelOk");
                }

                @Override
                public void handleCancel(String consumerTag) throws IOException {
                    logger.debug("handleCancel");
                }

                @Override
                public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
                    logger.debug("handleShutdownSignal");
                }

                @Override
                public void handleRecoverOk(String consumerTag) {
                    logger.debug("handleRecoverOk");
                }

                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties responseProps, byte[] body) throws IOException {
                    logger.debug("Consumed message from queue \"" + replyToQueue + "\": " + new String(body));
                    if (responseProps.getCorrelationId().equals(correlationId)) {
                        logger.debug("Received response with matching correlation ID: " + correlationId);
                        wait.offer(body);
                        try {
                            logger.debug("Canceling consumer " + consumerTag);
                            channel.basicCancel(consumerTag);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    else {
                        logger.debug("Received message with unexpected correlation ID: expected " + correlationId + ", but received " + responseProps.getCorrelationId());
                        throw new RuntimeException("Received message with unexpected correlation ID: expected " + correlationId + ", but received " + responseProps.getCorrelationId());
                    }
                }
            });
//            String consumerTag = channel.basicConsume(replyToQueue, true, new DefaultConsumer(channel) {
//                @Override
//                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties responseProps, byte[] body) {
//                    logger.debug("Consumed message from queue \"" + replyToQueue + "\": " + new String(body));
//                    if (responseProps.getCorrelationId().equals(correlationId)) {
//                        logger.debug("Received response with matching correlation ID: " + correlationId);
//                        wait.offer(body);
//                        try {
//                            logger.debug("Canceling consumer " + consumerTag);
//                            channel.basicCancel(consumerTag);
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
//                    }
//                    else {
//                        logger.debug("Received message with unexpected correlation ID: expected " + correlationId + ", but received " + responseProps.getCorrelationId());
//                        throw new RuntimeException("Received message with unexpected correlation ID: expected " + correlationId + ", but received " + responseProps.getCorrelationId());
//                    }
//                }
//            });
            logger.debug("Consumer " + consumerTag + " listening on queue \"" + replyToQueue + "\"");
        } catch (IOException e) {
            e.printStackTrace();
        }
        Response response = null;
        try {
            response = serializer.deserializeResponse(wait.take());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        logger.debug("Received response to login code request: " + response);
        return response;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String makeUuid() {
        return UUID.randomUUID().toString();
    }
}
