package com.integration.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class MailService {

    /**
     * 코드 메일은 회원가입·비밀번호 재설정 두 가지뿐이고, 레이아웃은 같고 문구만 다릅니다.
     * 템플릿을 복사하는 대신 문구만 여기에 모아둡니다.
     */
    private enum Purpose {
        SIGNUP("[모멘텀] 회원가입 인증 코드", "회원가입 인증", "환영합니다!",
                "회원가입을 완료해주세요.", "인증 코드"),
        PASSWORD_RESET("[모멘텀] 비밀번호 재설정 코드", "비밀번호 재설정", "비밀번호를 잊으셨나요?",
                "새 비밀번호를 설정해주세요.", "재설정 코드");

        final String subject;
        final String headerLabel;
        final String greeting;
        final String action;
        final String codeLabel;

        Purpose(String subject, String headerLabel, String greeting, String action, String codeLabel) {
            this.subject = subject;
            this.headerLabel = headerLabel;
            this.greeting = greeting;
            this.action = action;
            this.codeLabel = codeLabel;
        }
    }

    private final JavaMailSender mailSender;
    private final String from;

    public MailService(JavaMailSender mailSender,
                       @Value("${app.verification.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendVerificationCode(String to, String code, int ttlMinutes) {
        send(Purpose.SIGNUP, to, code, ttlMinutes);
    }

    public void sendPasswordResetCode(String to, String code, int ttlMinutes) {
        send(Purpose.PASSWORD_RESET, to, code, ttlMinutes);
    }

    private void send(Purpose purpose, String to, String code, int ttlMinutes) {
        MimeMessage msg = mailSender.createMimeMessage();
        try {
            // multipart=true so the plain-text alternative rides along for clients
            // that don't render HTML.
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(purpose.subject);
            helper.setText(plainBody(purpose, code, ttlMinutes), htmlBody(purpose, code, ttlMinutes));
        } catch (MessagingException e) {
            throw new MailSendException("Failed to build verification mail", e);
        }
        mailSender.send(msg);
    }

    private String plainBody(Purpose purpose, String code, int ttlMinutes) {
        return "모멘텀 " + purpose.headerLabel + "\n\n"
                + "아래 " + purpose.codeLabel + "를 입력해 " + purpose.action + "\n\n"
                + "    " + code + "\n\n"
                + ttlMinutes + "분 이내에 입력해야 유효합니다.\n"
                + "본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.";
    }

    /**
     * Table-based layout with inline styles — Outlook and most Korean webmail
     * clients strip &lt;style&gt; blocks and don't lay out flex/grid.
     */
    private String htmlBody(Purpose purpose, String code, int ttlMinutes) {
        return """
                <!doctype html>
                <html lang="ko">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background-color:#eaf0ff;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#eaf0ff;padding:32px 16px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 8px 24px rgba(99,119,200,0.15);">

                          <!-- header -->
                          <tr>
                            <td align="center" style="background-color:#7f93e6;background-image:linear-gradient(135deg,#7f93e6 0%%,#6377c8 100%%);padding:32px 24px;">
                              <div style="font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:24px;font-weight:700;color:#ffffff;letter-spacing:2px;">모멘텀</div>
                              <div style="font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:13px;color:#eaf0ff;margin-top:6px;">%1$s</div>
                            </td>
                          </tr>

                          <!-- body -->
                          <tr>
                            <td style="padding:36px 32px 8px 32px;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                              <p style="margin:0 0 8px 0;font-size:18px;font-weight:600;color:#2b3355;">%2$s</p>
                              <p style="margin:0;font-size:14px;line-height:1.7;color:#5c6584;">
                                아래 <strong style="color:#6377c8;">%3$s</strong>를 입력해 %4$s
                              </p>
                            </td>
                          </tr>

                          <!-- code -->
                          <tr>
                            <td align="center" style="padding:24px 32px;">
                              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%%" style="background-color:#f6f9ff;border:1px solid #cfe0ff;border-radius:12px;">
                                <tr>
                                  <!-- text-indent offsets the trailing letter-space so the code stays optically centered -->
                                  <td align="center" style="padding:22px 12px;font-family:'Apple SD Gothic Neo',Consolas,monospace;font-size:34px;font-weight:700;letter-spacing:8px;text-indent:8px;color:#3a6bd6;">
                                    %5$s
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>

                          <!-- ttl notice -->
                          <tr>
                            <td style="padding:0 32px 28px 32px;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%%" style="background-color:#fff5f7;border-left:3px solid #ff9db1;border-radius:6px;">
                                <tr>
                                  <td style="padding:12px 14px;font-size:13px;line-height:1.6;color:#b4566d;">
                                    이 코드는 <strong>%6$d분</strong> 이내에 입력해야 유효합니다.
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>

                          <!-- footer -->
                          <tr>
                            <td style="padding:18px 32px 26px 32px;border-top:1px solid #eaf0ff;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:12px;line-height:1.6;color:#9aa3bd;">
                              본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.<br>
                              이 메일은 발신 전용이며 회신하실 수 없습니다.
                            </td>
                          </tr>

                        </table>
                        <div style="font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:11px;color:#9aa3bd;margin-top:16px;">&copy; 2026 Geol2 &middot; big9401@gmail.com</div>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(purpose.headerLabel, purpose.greeting, purpose.codeLabel, purpose.action, code, ttlMinutes);
    }
}
