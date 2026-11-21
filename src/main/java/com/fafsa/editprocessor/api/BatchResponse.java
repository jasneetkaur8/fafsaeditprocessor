package com.fafsa.editprocessor.api;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/**
 * Result of a batch: one item per application, in request order.
 *
 * @param results each application's result
 */
public record BatchResponse(List<Item> results) {

    /**
     * One application's result: its position in the request array, then the same fields as a
     * single validation response ({@code status}, {@code message}, and any {@code errors},
     * {@code warnings} or {@code violations}).
     */
    @JsonPropertyOrder({"index"})
    public record Item(int index, @JsonUnwrapped ValidationResponse result) {
    }
}
