package com.sitemanager.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class ClarificationRequest {

    @NotEmpty(message = "Answers are required")
    private List<ClarificationAnswer> answers;

    private String senderName;

    public List<ClarificationAnswer> getAnswers() { return answers; }
    public void setAnswers(List<ClarificationAnswer> answers) { this.answers = answers; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public static class ClarificationAnswer {
        private String question;
        private String answer;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }
}
