package com.voicemail.tracker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Holds RingCentral OAuth application configuration.
 * No shared RestClient bean — each user session gets its own RestClient
 * instance built by RingCentralClientFactory after they authenticate.
 */
@Configuration
public class RingCentralConfig {

    @Value("${rc.client-id}")
    private String clientId;

    @Value("${rc.client-secret}")
    private String clientSecret;

    @Value("${rc.server-url:https://platform.ringcentral.com}")
    private String serverUrl;

    @Value("${rc.redirect-uri}")
    private String redirectUri;

    public String getClientId()     { return clientId; }
    public String getClientSecret() { return clientSecret; }
    public String getServerUrl()    { return serverUrl; }
    public String getRedirectUri()  { return redirectUri; }
}
