package com.sitemanager.dto;

import com.sitemanager.model.enums.ProjectDefinitionStatus;

import java.util.List;

public class ProjectDefinitionStateResponse {

    private Long sessionId;
    private ProjectDefinitionStatus status;
    private String currentQuestion;
    private String questionType;
    private List<String> options;
    private int progressPercent;
    private String generatedContent;
    private String prUrl;
    private String errorMessage;
    private boolean isEdit;

    public ProjectDefinitionStateResponse() {
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public ProjectDefinitionStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectDefinitionStatus status) {
        this.status = status;
    }

    public String getCurrentQuestion() {
        return currentQuestion;
    }

    public void setCurrentQuestion(String currentQuestion) {
        this.currentQuestion = currentQuestion;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getGeneratedContent() {
        return generatedContent;
    }

    public void setGeneratedContent(String generatedContent) {
        this.generatedContent = generatedContent;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isEdit() {
        return isEdit;
    }

    public void setIsEdit(boolean isEdit) {
        this.isEdit = isEdit;
    }
}
