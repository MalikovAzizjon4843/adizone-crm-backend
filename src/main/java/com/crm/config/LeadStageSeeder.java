package com.crm.config;

import com.crm.entity.LeadStage;
import com.crm.entity.enums.StageKind;
import com.crm.repository.LeadStageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bo'sh {@code lead_stages} jadvalini boshlang'ich to'qqizta bosqich
 * bilan to'ldiradi.
 *
 * <p>{@link ApplicationRunner} Hibernate schema update'dan KEYIN ishlaydi —
 * {@code EnumCheckConstraintCleaner} bilan bir xil mexanizm.
 *
 * <p><b>Faqat bo'sh jadvalga yoziladi.</b> Bitta qator bo'lsa ham seed
 * butunlay o'tkazib yuboriladi: buyurtmachi tahrirlagan nomlar, ranglar va
 * tartib har ishga tushishda tiklanib ketmasin. Shu sababli bu yerda
 * "yangi bosqichni qo'shib qo'yish" mantiqi ham yo'q — u bir kunda
 * o'chirilgan bosqichni qaytarib kelardi.
 *
 * <p>Kodlar bazadagi {@code leads.status} qiymatlari bilan bir xil —
 * avval bu qiymatlar enumdan kelardi, endi shu jadvaldan.
 *
 * <p>Uch tildagi nomlar frontend {@code src/locales/*.js} dagi
 * {@code leads.statusKanban} bilan bir xil bo'lishi kerak.
 *
 * <p>{@code requiresAmount} faqat to'lov bosqichlarida true — o'sha
 * bosqichga o'tishda summa so'raladi va avtomatik izoh yoziladi.
 *
 * <p>O'chirish uchun: {@code app.lead-stages.seed: false}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(100)
@ConditionalOnProperty(
    name = "app.lead-stages.seed",
    havingValue = "true",
    matchIfMissing = true)
public class LeadStageSeeder implements ApplicationRunner {

    private final LeadStageRepository leadStageRepository;

    @Override
    public void run(ApplicationArguments args) {
        long existing;
        try {
            existing = leadStageRepository.count();
        } catch (Exception e) {
            log.error("lead_stages jadvalini o'qib bo'lmadi — seed o'tkazib yuborildi", e);
            return;
        }
        if (existing > 0) {
            log.info("lead_stages allaqachon to'ldirilgan ({} ta) — seed o'tkazib yuborildi", existing);
            return;
        }

        List<LeadStage> stages = List.of(
            stage(1, "NEW", "Yangi", "Новый", "New",
                "secondary", StageKind.OPEN, false),
            stage(2, "CONTACTED", "Bog'lanildi", "Связались", "Contacted",
                "info", StageKind.OPEN, false),
            stage(3, "ONLINE_ENROLLED", "Online yozildi",
                "Онлайн записан", "Enrolled online",
                "warning", StageKind.OPEN, false),
            stage(4, "OFFLINE_ENROLLED", "Offline yozildi",
                "Офлайн записан", "Enrolled offline",
                "warning", StageKind.OPEN, false),
            stage(5, "ONLINE_PAID", "Online to'ladi",
                "Онлайн оплатил", "Paid online",
                "success", StageKind.OPEN, true),
            stage(6, "OFFLINE_PAID", "Offline to'ladi",
                "Офлайн оплатил", "Paid offline",
                "success", StageKind.OPEN, true),
            // Ikkita konvert bosqichi: o'quvchi onlayn yoki oflayn o'qiydi va
            // kanbanda alohida ustunlarda turadi. Qaysi biriga tushishini
            // konvert so'rovidagi studyFormat hal qiladi.
            stage(7, "CONVERTED_ONLINE", "Online o'quvchi",
                "Онлайн ученик", "Online student",
                "success", StageKind.CONVERTED, false),
            stage(8, "CONVERTED_OFFLINE", "Offline o'quvchi",
                "Офлайн ученик", "Offline student",
                "success", StageKind.CONVERTED, false),
            stage(9, "REJECTED", "Rad etildi",
                "Отклонён", "Rejected",
                "danger", StageKind.REJECTED, false));

        try {
            leadStageRepository.saveAll(stages);
            log.info("lead_stages: {} ta bosqich yozildi", stages.size());
        } catch (Exception e) {
            // Seed ilovani yiqitmaydi — bosqichlar qo'lda ham qo'shilishi mumkin
            log.error("lead_stages seed bajarilmadi", e);
        }
    }

    private static LeadStage stage(int sortOrder, String code,
                                   String nameUz, String nameRu, String nameEn,
                                   String color, StageKind kind,
                                   boolean requiresAmount) {
        return LeadStage.builder()
            .code(code)
            .nameUz(nameUz)
            .nameRu(nameRu)
            .nameEn(nameEn)
            .color(color)
            .sortOrder(sortOrder)
            .kind(kind)
            .requiresAmount(requiresAmount)
            .isActive(true)
            .build();
    }
}
