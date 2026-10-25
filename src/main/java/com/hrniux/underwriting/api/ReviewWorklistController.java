package com.hrniux.underwriting.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hrniux.underwriting.review.ReviewWorklistPage;
import com.hrniux.underwriting.review.ReviewWorklistService;
import com.hrniux.underwriting.review.ReviewWorklistStatus;
import com.hrniux.underwriting.rule.Decision;

@RestController
@RequestMapping("/api/v1/underwriting/review-worklist")
public class ReviewWorklistController {

    private final ReviewWorklistService worklist;

    public ReviewWorklistController(ReviewWorklistService worklist) {
        this.worklist = worklist;
    }

    @GetMapping
    public ReviewWorklistPage list(
            @RequestParam(required = false) ReviewWorklistStatus status,
            @RequestParam(required = false) Decision decision,
            @RequestParam(required = false) String policyNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return worklist.query(status, decision, policyNo, page, size);
    }
}
