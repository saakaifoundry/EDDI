package ai.labs.facebookmessenger.endpoint;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.memory.model.Deployment;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.runtime.SystemRuntime;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.URIUtilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.messenger4j.MessengerPlatform;
import com.github.messenger4j.exceptions.MessengerApiException;
import com.github.messenger4j.exceptions.MessengerIOException;
import com.github.messenger4j.exceptions.MessengerVerificationException;
import com.github.messenger4j.receive.MessengerReceiveClient;
import com.github.messenger4j.receive.handlers.QuickReplyMessageEventHandler;
import com.github.messenger4j.receive.handlers.TextMessageEventHandler;
import com.github.messenger4j.send.MessengerSendClient;
import com.github.messenger4j.send.NotificationType;
import com.github.messenger4j.send.QuickReply;
import com.github.messenger4j.send.SenderAction;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.jboss.resteasy.plugins.guice.RequestScoped;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.TimeoutHandler;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static ai.labs.rest.restinterfaces.RestInterfaceFactory.RestInterfaceFactoryException;

@RequestScoped
@Slf4j
public class FacebookEndpoint implements IFacebookEndpoint {
    private static final String RESOURCE_URI_CHANNELCONNECTOR = "eddi://ai.labs.channel.facebook";
    private static final String AI_LABS_USER_AGENT = "Jetty 9.4/HTTP CLIENT - AI.LABS.EDDI";
    private static final String ENCODING = "UTF-8";
    private static final long timeoutInMillis = 10000;


    private final IBotStore botStore;
    private final IHttpClient httpClient;
    private final IJsonSerialization jsonSerialization;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final String apiServerURI;
    private final ICache<String, BotConfiguration> botConfigCache;
    private final ICache<String, String> conversationIdCache;
    private final ICache<String, MessengerClient> messengerClientCache;

    @Inject
    public FacebookEndpoint(IBotStore botStore,
                            IHttpClient httpClient,
                            IJsonSerialization jsonSerialization,
                            IRestInterfaceFactory restInterfaceFactory,
                            @Named("system.apiServerURI") String apiServerURI,
                            ICacheFactory cacheFactory) {
        this.botStore = botStore;
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.restInterfaceFactory = restInterfaceFactory;
        this.apiServerURI = apiServerURI;
        this.botConfigCache = cacheFactory.getCache("facebook.botConfiguration");
        this.conversationIdCache = cacheFactory.getCache("facebook.conversationIds");
        this.messengerClientCache = cacheFactory.getCache("facebook.messengerReceiveClients");
    }

    private MessengerClient getMessageClient(String botId) {
        if (!messengerClientCache.containsKey(botId)) {
            messengerClientCache.put(botId, createMessageClient(botId));
        }

        return messengerClientCache.get(botId);
    }

    private MessengerClient createMessageClient(String botId) {
        BotConfiguration botConfiguration;
        try {
            Integer botVersion = botStore.getCurrentResourceId(botId).getVersion();
            String cacheKey = botId + ":" + botVersion;
            if ((botConfiguration = botConfigCache.get(cacheKey)) == null) {
                botConfiguration = botStore.read(botId, botVersion);
                botConfigCache.put(cacheKey, botConfiguration);
            }
        } catch (Exception e) {
            throw new WebApplicationException("Could not read bot configuration", e);
        }

        String appSecret = null;
        String verificationToken = null;
        String pageAccessToken = null;
        for (BotConfiguration.ChannelConnector channelConnector : botConfiguration.getChannels()) {
            if (channelConnector.getType().toString().equals(RESOURCE_URI_CHANNELCONNECTOR)) {
                Map<String, String> channelConnectorConfig = channelConnector.getConfig();
                appSecret = channelConnectorConfig.get("appSecret");
                verificationToken = channelConnectorConfig.get("verificationToken");
                pageAccessToken = channelConnectorConfig.get("pageAccessToken");
                break;
            }
        }

        if (appSecret == null || verificationToken == null || pageAccessToken == null) {
            throw new IllegalArgumentException("appSecret, verificationToken and pageAccessToken must not be <null>.");
        }

        return new MessengerClient(MessengerPlatform.newSendClientBuilder(pageAccessToken).build(),
                MessengerPlatform.newReceiveClientBuilder(appSecret, verificationToken)
                        .onTextMessageEvent(getTextMessageEventHandler(botId, Deployment.Environment.unrestricted))
                        .onQuickReplyMessageEvent(getQuickMessageEventHandler(botId, Deployment.Environment.unrestricted))
                        .build());
    }

    private TextMessageEventHandler getTextMessageEventHandler(String botId, Deployment.Environment environment) {
        return event -> {
            try {
                String message = event.getText();
                String senderId = event.getSender().getId();
                final String conversationId = getConversationId(environment, botId, senderId);
                say(environment, botId, conversationId, senderId, message);

            } catch (RestInterfaceFactoryException | IRequest.HttpRequestException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        };
    }

    private QuickReplyMessageEventHandler getQuickMessageEventHandler(String botId, Deployment.Environment environment) {
        return event -> {
            try {
                String message = event.getQuickReply().getPayload();
                String senderId = event.getSender().getId();
                final String conversationId = getConversationId(environment, botId, senderId);
                say(environment, botId, conversationId, senderId, message);

            } catch (RestInterfaceFactoryException | IRequest.HttpRequestException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        };
    }


    private void say(Deployment.Environment environment,
                     String botId,
                     String conversationId,
                     String senderId,
                     String message) throws IRequest.HttpRequestException {

        URI uri = RestUtilities.createURI(
                apiServerURI, "/bots/",
                environment, "/",
                botId, "/",
                conversationId);

        try {
            messengerClientCache.get(botId).getSendClient().sendSenderAction(senderId, SenderAction.TYPING_ON);
        } catch (MessengerApiException | MessengerIOException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        final String jsonRequestBody = "{ \"input\": \"" + message + "\", \"context\": {} }";
        try {
            IResponse httpResponse = httpClient.newRequest(uri, IHttpClient.Method.POST)
                    .setUserAgent(AI_LABS_USER_AGENT)
                    .setTimeout(10000, TimeUnit.MILLISECONDS)
                    .setBodyEntity(jsonRequestBody, ENCODING, MediaType.APPLICATION_JSON)
                    .send();
            log.debug("response: {}", httpResponse.getContentAsString());
            final List<String> output = getOutputText(httpResponse.getContentAsString());
            final List<Map<String, String>> quickReplies = getQuickReplies(httpResponse.getContentAsString());

            try {
                messengerClientCache.get(botId).getSendClient().sendSenderAction(senderId, SenderAction.TYPING_OFF);
            } catch (MessengerApiException | MessengerIOException e) {
                log.error(e.getLocalizedMessage(), e);
            }

            List<QuickReply> fbQuickReplies = new ArrayList<>();
            if (quickReplies != null && quickReplies.size() > 0) {
                QuickReply.ListBuilder listBuilder = QuickReply.newListBuilder();
                for (Map<String, String> quickReply : quickReplies) {
                    listBuilder.addTextQuickReply(quickReply.get("value"), quickReply.get("expressions")).toList();
                }
               fbQuickReplies = listBuilder.build();
            }

            for (String outputText : output) {
                if (fbQuickReplies != null && !fbQuickReplies.isEmpty()) {
                    messengerClientCache.get(botId).getSendClient().
                            sendTextMessage(senderId, outputText, fbQuickReplies);
                } else {
                    messengerClientCache.get(botId).getSendClient().
                            sendTextMessage(senderId, outputText);
                }
            }


            final String state = getConversationState(httpResponse.getContentAsString());
            if (state != null && !state.equals("READY")) {
                conversationIdCache.remove(senderId);
            }
        } catch (MessengerIOException | MessengerApiException e) {
            log.error(e.getLocalizedMessage(), e);
        }


    }

    private List<Map<String,String>> getQuickReplies(String json) {
        List<Map<String,String>> output = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            JsonNode conversationStepsArray = rootNode.path("conversationSteps");
            for (JsonNode conversationStep : conversationStepsArray) {
                for (JsonNode conversationStepValues : conversationStep.get("conversationStep")) {
                    if (conversationStepValues.get("key") != null && conversationStepValues.get("key").asText().startsWith("quickReplies")) {
                        if (conversationStepValues.get("value").isArray()) {
                            for (JsonNode node : conversationStepValues.get("value")) {
                                HashMap<String, String> map = new HashMap<>();
                                map.put("value", node.get("value").asText());
                                map.put("expressions", node.get("expressions").asText());
                                output.add(map);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("json parsing error", e);
        }

        return output;
    }

    private List<String> getOutputText(String json) {

        List<String> output = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            JsonNode conversationStepsArray = rootNode.path("conversationSteps");
            for (JsonNode conversationStep : conversationStepsArray) {
                for (JsonNode conversationStepValues : conversationStep.get("conversationStep")) {
                    if (conversationStepValues.get("key") != null && conversationStepValues.get("key").asText().startsWith("output:text")) {
                        output.add(conversationStepValues.get("value").asText());
                    }
                }
            }
        } catch (IOException e) {
            log.error("json parsing error", e);
        }

        return output;
    }

    private String getConversationState(String json) {

        String state = null;

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            JsonNode conversationState = rootNode.path("conversationState");
            if (conversationState != null) {
                state = conversationState.asText();
            }
        } catch (IOException e) {
            log.error("json parsing error", e);
        }

        return state;
    }


    private String getConversationId(Deployment.Environment environment, String botId, String senderId)
            throws RestInterfaceFactoryException {

        String conversationId;
        if ((conversationId = conversationIdCache.get(senderId)) == null) {
            conversationId = createConversation(environment, botId, senderId);
            conversationIdCache.put(senderId, conversationId);
        }

        return conversationId;
    }

    private String createConversation(Deployment.Environment environment, String botId, String senderId)
            throws RestInterfaceFactoryException {
        String conversationId;
        try {
            Response response = restInterfaceFactory.get(IRestBotEngine.class, apiServerURI).
                    startConversation(environment, botId);
            if (response.getStatus() == 201) {
                URIUtilities.ResourceId resourceIdConversation =
                        URIUtilities.extractResourceId(response.getLocation());
                conversationId = resourceIdConversation.getId();
                conversationIdCache.put(senderId, conversationId);
                return conversationId;
            }
            Exception e = new Exception("bot (id:" + botId + ") is not deployed");
            throw new RestInterfaceFactoryException(e.getLocalizedMessage(), e);
        } catch (RestInterfaceFactoryException e) {
            log.error(e.getLocalizedMessage(), e);
            throw e;
        }
    }

    @Override
    public Response webHook(final String botId, final String callbackPayload, final String sha1PayloadSignature) {
        SystemRuntime.getRuntime().submitCallable((Callable<Void>) () -> {
            try {
                log.info("webhook called");
                getMessageClient(botId).getReceiveClient().
                        processCallbackPayload(callbackPayload, sha1PayloadSignature);
            } catch (MessengerVerificationException e) {
                log.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException("Error when processing callback payload");
            }
            return null;
        }, null);

        return Response.ok().build();
    }

    @Override
    public Response webHookSetup(final String botId, final String mode,
                                 final String verificationToken, final String challenge) {
        try {
            log.info("webhook setup called");
            return Response.ok(getMessageClient(botId).getReceiveClient().
                    verifyWebhook(mode, verificationToken, challenge)).build();
        } catch (MessengerVerificationException e) {
            log.error(e.getLocalizedMessage(), e);
            return Response.status(Response.Status.FORBIDDEN).build();
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    private static class MessengerClient {
        MessengerSendClient sendClient;
        MessengerReceiveClient receiveClient;
    }
}
