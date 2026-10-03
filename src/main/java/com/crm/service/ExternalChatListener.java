package com.crm.service;

import com.crm.entity.Conversation;
import com.crm.entity.Message;
import com.crm.entity.User;

/**
 * EXTERNAL suhbatda (Mini App bilan, telegram-platform §11.3) xodim xabar yozganda — {@link ChatService#send}
 * tranzaksiyasi ichida chaqiriladi. Amalga oshiruvchi — Mini App bot push'i (outbox).
 */
public interface ExternalChatListener {

    void onStaffMessage(Conversation conversation, User sender, Message message);
}
