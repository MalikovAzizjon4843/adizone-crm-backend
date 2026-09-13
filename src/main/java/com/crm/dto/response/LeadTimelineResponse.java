package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lid kartasining lentasi.
 *
 * <p>{@code openTasks} ataylab lentadan tashqarida: amoCRM'da ochiq vazifa
 * xronologiyaning bir qismi emas, kartaning pastida fiksirlangan holda
 * turadi va u yerdan bajariladi. Aralashtirilsa u sana bo'yicha lenta
 * ichida "cho'kib" ketardi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadTimelineResponse {

    private List<LeadTimelineItemDto> items;

    private PageInfo page;

    /** Shu lidning OPEN vazifalari, muddat bo'yicha o'sish tartibida. */
    private List<TaskResponse> openTasks;

    /** Maydon nomlari {@code PageResponse} bilan bir xil. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageInfo {
        private int pageNumber;
        private int pageSize;
        private long totalElements;
        private int totalPages;
        private boolean last;
    }
}
