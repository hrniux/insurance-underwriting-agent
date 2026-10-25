package com.hrniux.underwriting.review;

import java.util.List;

public record ReviewWorklistPage(
        List<ReviewWorklistItem> items,
        int page,
        int size,
        int totalElements,
        int totalPages) {

    public ReviewWorklistPage {
        items = List.copyOf(items);
    }
}
