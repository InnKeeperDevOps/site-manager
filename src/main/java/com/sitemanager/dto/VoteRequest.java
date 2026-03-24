package com.sitemanager.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class VoteRequest {

    @Min(-1)
    @Max(1)
    private int value;

    private String voterIdentifier;

    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }
    public String getVoterIdentifier() { return voterIdentifier; }
    public void setVoterIdentifier(String voterIdentifier) { this.voterIdentifier = voterIdentifier; }
}
