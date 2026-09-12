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
 * <p>Lidga bog'lanmagan vazifada (o'quvchi vazifasi yoki mustaqil vazifa)
 * {@code leadId} null, {@code leadHasOpenTask} esa {@code false} — frontend
 * bu holatda taklifni ko'rsatmaydi.
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
