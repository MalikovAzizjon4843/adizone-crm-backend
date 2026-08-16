package com.crm.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    /**
     * Ixtiyoriy qo'shimcha ma'lumot (aggregat, sahifadan tashqari hisoblar).
     * NON_NULL — buni ishlatmaydigan endpointlar javobi o'zgarmaydi.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object meta;
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder().success(true).data(data).build();
    }
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder().success(true).message(message).data(data).build();
    }
    /** data o'z joyida qoladi, aggregat yonida qo'shimcha maydon sifatida keladi. */
    public static <T> ApiResponse<T> successWithMeta(T data, Object meta) {
        return ApiResponse.<T>builder().success(true).data(data).meta(meta).build();
    }
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder().success(false).message(message).build();
    }
}
