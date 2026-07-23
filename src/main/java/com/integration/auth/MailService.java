package com.integration.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String from;

    public MailService(JavaMailSender mailSender,
                       @Value("${app.verification.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendVerificationCode(String to, String code, int ttlMinutes) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("[별빛] 이메일 인증 코드");
        msg.setText(
                "아래 인증 코드를 입력해 회원가입을 완료해주세요.\n\n"
                + "    " + code + "\n\n"
                + ttlMinutes + "분 이내에 입력해야 유효합니다.\n"
                + "본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.");
        mailSender.send(msg);
    }
}
