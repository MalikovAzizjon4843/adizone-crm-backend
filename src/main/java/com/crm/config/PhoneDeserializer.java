package com.crm.config;

import com.crm.util.PhoneUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Kelgan telefonni validatsiyadan OLDIN kanonik shaklga keltiradi.
 *
 * <p>Muammo: frontend "+998 90 123 45 67" ko'rinishidagi formatlangan
 * qiymatni yuborardi va {@code @Pattern} uni rad etardi. Foydalanuvchi
 * o'zicha to'g'ri raqam yozgani holda 400 olardi.
 *
 * <p>Jackson deserializatsiyasi Bean Validation dan oldin ishlaydi, shuning
 * uchun regexlar tegilmagan holda qoldi: ular endi doim tozalangan qiymatni
 * ko'radi. Tanib bo'lmagan qiymat XOM holicha o'tadi va o'sha regex uni rad
 * etadi — xato xabari o'zgarmaydi.
 *
 * <p>JSON {@code null} da Jackson bu klassni umuman chaqirmaydi, ya'ni null
 * null bo'lib qoladi. Raqam sifatida kelgan qiymat ({@code 901234567})
 * {@link JsonParser#getValueAsString()} orqali matnga aylanadi.
 */
public class PhoneDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        return PhoneUtils.canonicalOrRaw(parser.getValueAsString());
    }
}
