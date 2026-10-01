package com.crm.billing.support;

import com.crm.repository.CashRegisterRepository;
import com.crm.repository.CourseRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@TestConfiguration
public class BillingTestConfig {

    /** {@code billingClock} o'rniga — {@code @Primary} bo'lgani uchun tip bo'yicha inject shu. */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    public BillingFixtures billingFixtures(
            CourseRepository courseRepository,
            GroupRepository groupRepository,
            StudentRepository studentRepository,
            StudentGroupRepository studentGroupRepository,
            CashRegisterRepository cashRegisterRepository,
            UserRepository userRepository,
            TeacherRepository teacherRepository,
            TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate) {
        return new BillingFixtures(courseRepository, groupRepository, studentRepository,
            studentGroupRepository, cashRegisterRepository, userRepository, teacherRepository,
            transactionTemplate, jdbcTemplate);
    }
}
