package com.mieai.qqbot.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qqbot.security.app-secret")
public class AppSecretEncryptionProperties {

    private String keyId = "primary";
    private String masterKey = "";
    private String masterKeyFile = "";

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public String getMasterKeyFile() {
        return masterKeyFile;
    }

    public void setMasterKeyFile(String masterKeyFile) {
        this.masterKeyFile = masterKeyFile;
    }
}
