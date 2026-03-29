package com.sitemanager.controller;

import com.sitemanager.dto.ContributorStatsDto;
import com.sitemanager.dto.SuggestionSummaryDto;
import com.sitemanager.service.ContributorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contributors")
public class ContributorController {

    private final ContributorService contributorService;

    public ContributorController(ContributorService contributorService) {
        this.contributorService = contributorService;
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<ContributorStatsDto>> getLeaderboard() {
        return ResponseEntity.ok(contributorService.getLeaderboard());
    }

    @GetMapping("/{authorId}/history")
    public ResponseEntity<List<SuggestionSummaryDto>> getUserHistory(@PathVariable String authorId) {
        List<SuggestionSummaryDto> history = contributorService.getUserHistory(authorId);
        return ResponseEntity.ok(history);
    }
}
