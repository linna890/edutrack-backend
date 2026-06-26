package com.edutrack.service;

import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final JavaMailSender mailSender;

    @Value("${edutrack.school.name}")
    private String schoolName;

    @Value("${edutrack.school.email-from}")
    private String emailFrom;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends an HTML attendance alert email to the student's parent.
     * Runs synchronously so the caller (AttendanceController) can catch failures
     * and correctly manage the parentNotified flag.
     */
    public void sendAttendanceAlert(Student student, AttendanceRecord.Status status) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(emailFrom);
            helper.setTo(student.getParentEmail());
            helper.setSubject(buildSubject(student, status));
            helper.setText(buildHtmlBody(student, status), true);

            mailSender.send(message);
            log.info("Attendance alert sent for student {} to {}", student.getStudentId(), student.getParentEmail());

        } catch (MessagingException e) {
            log.error("Failed to send attendance alert for student {}: {}", student.getStudentId(), e.getMessage());
            throw new RuntimeException("Email send failed: " + e.getMessage(), e);
        }
    }

    private String buildSubject(Student student, AttendanceRecord.Status status) {
        return switch (status) {
            case ABSENT -> "⚠️ " + student.getFullName() + " was absent today — " + schoolName;
            case LATE   -> "⏰ " + student.getFullName() + " arrived late today — " + schoolName;
            default     -> student.getFullName() + " attendance update — " + schoolName;
        };
    }

    private String buildHtmlBody(Student student, AttendanceRecord.Status status) {
        String dateStr  = LocalDate.now().format(DATE_FMT);
        String color    = status == AttendanceRecord.Status.ABSENT ? "#FF8A80" : "#FFD54F";
        String icon     = status == AttendanceRecord.Status.ABSENT ? "❌" : "⏰";
        String headline = status == AttendanceRecord.Status.ABSENT
                ? "was marked <strong>ABSENT</strong> today"
                : "arrived <strong>LATE</strong> today";

        return """
            <!DOCTYPE html>
            <html>
            <body style="margin:0;padding:0;font-family:Arial,sans-serif;background:#F0F9FF;">
              <div style="max-width:560px;margin:40px auto;background:white;border-radius:20px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                <div style="background:linear-gradient(135deg,#1E3A5F,#0F2240);padding:32px 40px;text-align:center;">
                  <div style="font-size:48px;margin-bottom:8px;">🏫</div>
                  <h1 style="color:white;font-size:22px;margin:0;">%s</h1>
                  <p style="color:rgba(255,255,255,0.6);font-size:13px;margin:6px 0 0;">Smart Attendance Notification</p>
                </div>
                <div style="padding:36px 40px;">
                  <div style="background:%s20;border-left:4px solid %s;border-radius:12px;padding:20px 24px;margin-bottom:28px;text-align:center;">
                    <div style="font-size:36px;">%s</div>
                    <p style="font-size:16px;color:#1E293B;margin:8px 0 0;">
                      <strong>%s</strong> %s.
                    </p>
                    <p style="font-size:13px;color:#64748B;margin:6px 0 0;">%s</p>
                  </div>
                  <table style="width:100%%;border-collapse:collapse;margin-bottom:28px;">
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Student</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Grade / Class</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;color:#64748B;font-size:13px;">Student ID</td>
                        <td style="padding:10px 0;font-weight:600;font-size:13px;">%s</td></tr>
                  </table>
                  <p style="font-size:13px;color:#64748B;line-height:1.6;">
                    If you believe this is an error or your child was present, please contact the school office immediately. This notification was sent automatically by the EduTrack attendance system.
                  </p>
                </div>
                <div style="background:#F8FBFF;padding:20px 40px;text-align:center;border-top:1px solid #E2EFF9;">
                  <p style="font-size:12px;color:#94A3B8;margin:0;">© 2026 %s · Powered by EduTrack</p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                schoolName,
                color, color, icon,
                student.getFullName(), headline,
                dateStr,
                student.getFullName(),
                student.getGrade(),
                student.getStudentId(),
                schoolName
        );
    }
}
