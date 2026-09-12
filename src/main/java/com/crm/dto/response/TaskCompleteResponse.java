package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vazifa yopilgandan keyingi javob.
 *
 * <p>{@code leadHasOpenTask = false} bo'lsa frontend darhol "yangi vazifa
 * qo'shing" oynasini ko'rsatadi — amoCRM ham shunday qiladi va aynan shu
 * narsa lidning "Без задач" ro'yxatiga tushib qolishiga yo'l qo'ymaydi.
 *
 * <p>O'quvchiga bog'langan vazifada {@code leadId} null, {@code leadHasOpenTask}
 * esa {@code false} — frontend bu holatda taklifni ko'rsatmaydi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCompleteResponse {
    private TaskResponse task;
    private Long leadId;
    private boolean leadHasOpenTask;
}
