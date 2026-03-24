package com.sitemanager.dto;

import jakarta.validation.constraints.NotBlank;

public class MessageRequest {

    @NotBlank(message = "Message content is required")
    private String content;

    private String senderName;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
}
