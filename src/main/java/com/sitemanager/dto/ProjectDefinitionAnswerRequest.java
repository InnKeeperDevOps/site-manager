package com.sitemanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ProjectDefinitionAnswerRequest {

    @NotBlank
    @Size(max = 10000)
    private String answer;

    public ProjectDefinitionAnswerRequest() {
    }

    public ProjectDefinitionAnswerRequest(String answer) {
        this.answer = answer;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
