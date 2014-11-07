package net.whydah.identity.admin;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import net.whydah.identity.admin.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.MissingResourceException;

public class TokenServiceClient {
    private static final Logger logger = LoggerFactory.getLogger(TokenServiceClient.class);

    private final Client tokenServiceClient = Client.create();
    private final URI tokenServiceUri;
    private String myAppTokenXml;
    private String myAppTokenId;
    private String myUserTokenId;


    public TokenServiceClient() throws IOException {
        try {
            tokenServiceUri = UriBuilder.fromUri(AppConfig.readProperties().getProperty("tokenservice")).build();
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getLocalizedMessage(), e);
        }
    }


    public String getUserTokenFromUserTokenId(String usertokenid) {
        logonApplication();
        WebResource userTokenResource = tokenServiceClient.resource(tokenServiceUri).path("user/" + myAppTokenId + "/get_usertoken_by_usertokenid");
        MultivaluedMap<String, String> formData = new MultivaluedMapImpl();
        formData.add("apptoken", myAppTokenXml);
        formData.add("usertokenid", usertokenid);
        logger.trace("getUserTokenFromUserTokenId - calling={} with usertokenid={} ", myAppTokenId, usertokenid);
        ClientResponse response = userTokenResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.FORBIDDEN.getStatusCode()) {
            throw new IllegalArgumentException("getUserTokenFromUserTokenId - get_usertoken_by_usertokenid failed.");
        }
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.trace("getUserTokenFromUserTokenId - Response OK with XML: {}", responseXML);
            return responseXML;
        }
        //retry
        response = userTokenResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.trace("getUserTokenFromUserTokenId - Response OK with XML: {}", responseXML);
            return responseXML;
        }

        throw new RuntimeException("getUserTokenFromUserTokenId - get_usertoken_by_usertokenid failed with status code " + response.getStatus());
    }

    private void logonApplication() {
        //todo sjekke om myAppTokenXml er gyldig før reauth
        WebResource logonResource = tokenServiceClient.resource(tokenServiceUri).path("logon");
        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        ApplicationCredential appCredential = new ApplicationCredential();
        try {
            String applicationid = AppConfig.readProperties().getProperty("applicationid");
            String applicationsecret = AppConfig.readProperties().getProperty("applicationsecret");

            appCredential.setApplicationID(applicationid);
            appCredential.setApplicationPassord(applicationsecret);

            formData.add("applicationcredential", appCredential.toXML());
            ClientResponse response = logonResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
            //todo håndtere feil i statuskode + feil ved app-pålogging (retry etc)
            if (response.getStatus() != 200) {
                logger.error("logonApplication - Application authentication failed with statuscode {}", response.getStatus());
                throw new RuntimeException("Application authentication failed");
            }
            myAppTokenXml = response.getEntity(String.class);
            myAppTokenId = UserTokenXpathHelper.getApplicationTokenIdFromAppTokenXML(myAppTokenXml);
            logger.trace("logonApplication - Applogon ok: apptokenxml: {}", myAppTokenXml);
            logger.trace("logonApplication - myAppTokenId: {}", myAppTokenId);
        } catch (IOException ioe){
            logger.warn("logonApplication - Did not find configuration for my application credential.", ioe);
        }
    }



    public static boolean hasUserAdminRight(String userTokenXml) {
        if (userTokenXml == null) {
            logger.trace("hasUserAdminRight - Empty  userToken");
            return false;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(userTokenXml)));
            XPath xPath = XPathFactory.newInstance().newXPath();

            String expression = "/usertoken/application[@ID=\"19\"]/role[@name=\"WhydahUserAdmin\"]/@value";
            XPathExpression xPathExpression = xPath.compile(expression);
            logger.trace("hasUserAdminRight - token" + userTokenXml + "\nvalue:" + xPathExpression.evaluate(doc));
            String v = (xPathExpression.evaluate(doc));
            if (v == null || v.length() < 1) {
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("getTimestamp - userTokenXml timestamp parsing error", e);
        }
        return false;
    }



    public String getUserTokenByUserTicket(String userticket) {
        logonApplication();

        WebResource userTokenResource = tokenServiceClient.resource(tokenServiceUri).path("user/" + myAppTokenId + "/get_usertoken_by_userticket");
        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        formData.add("apptoken", myAppTokenXml);
        formData.add("userticket", userticket);
        ClientResponse response = userTokenResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.FORBIDDEN.getStatusCode()) {
            throw new IllegalArgumentException("Login failed.");
        }
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.trace("Response OK with XML: {}", responseXML);
            myUserTokenId = UserTokenXpathHelper.getUserTokenIdFromUserTokenXML(responseXML);
            return responseXML;
        }
        //retry
        response = userTokenResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        if (response.getStatus() == ClientResponse.Status.OK.getStatusCode()) {
            String responseXML = response.getEntity(String.class);
            logger.trace("Response OK with XML: {}", responseXML);
            return responseXML;
        }
        logger.warn("User authentication failed: {}", response);
        if (response.getStatus() == Response.Status.GONE.getStatusCode()) {
            throw new MissingResourceException("No token found for ticket.", getClass().getSimpleName(), userticket);
        }
        throw new RuntimeException("User authentication failed with status code " + response.getStatus());
    }

    public String getMyAppTokenId(){
        return myAppTokenId;
    }
    public String getMyUserTokenId(){
        return myUserTokenId;
    }


    /*
    private int calculateTokenRemainingLifetime(String userxml) {
        int tokenLifespan = Integer.parseInt(XPATHHelper.getLifespan(userxml));
        long tokenTimestamp = Long.parseLong(XPATHHelper.getTimestamp(userxml));
        long endOfTokenLife = tokenTimestamp + tokenLifespan;
        long remainingLife_ms = endOfTokenLife - System.currentTimeMillis();
        return (int)remainingLife_ms/1000;
    }


    private PostMethod setUpGetUserToken(PostMethod p,String userTokenId) throws IOException {
        String appTokenXML = p.getResponseBodyAsString();
        String applicationtokenid = XPATHHelper.getApplicationTokenIdFromAppTokenXML(appTokenXML);
        WebResource resource = tokenServiceClient.resource(tokenServiceUri).path("user/" + applicationtokenid + "/get_usertoken_by_usertokenid");

        PostMethod p2 = new PostMethod(resource.toString());
        p2.addParameter("apptoken",appTokenXML);
        p2.addParameter("usertokenid",userTokenId);

        logger.trace("apptoken:" + appTokenXML);
        logger.trace("usertokenid:" + userTokenId);
        return p2;
    }

    private PostMethod setupRealApplicationLogon() {
        ApplicationCredential acred = new ApplicationCredential();
        try {
            acred = new ApplicationCredential();
            Properties properties = AppConfig.readProperties();

            acred.setApplicationID(properties.getProperty("applicationname"));
            acred.setApplicationPassord(properties.getProperty("applicationname"));

        } catch (IOException ioe) {
            logger.error("Unable to get my application credentials from propertyfile.", ioe);
        }
        WebResource resource = tokenServiceClient.resource(tokenServiceUri).path("/logon");

        PostMethod p = new PostMethod(resource.toString());
        p.addParameter("applicationcredential",acred.toXML());
        return p;
    }
    */
}
