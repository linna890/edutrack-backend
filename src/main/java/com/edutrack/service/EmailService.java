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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private final JavaMailSender mailSender;

    @Value("${edutrack.school.name}")
    private String schoolName;

    @Value("${edutrack.school.email-from}")
    private String emailFrom;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ── Public send methods ──────────────────────────────────────────────────

    /** Student arrived on time — sent immediately on ARRIVAL scan before 8 AM */
    public void sendArrivalAlert(Student student, LocalDateTime arrivalTime) {
        send(student,
             "✅ " + student.getFullName() + " has arrived at school — " + schoolName,
             buildCard(student, arrivalTime, null));
    }

    /** Student arrived late — sent immediately on ARRIVAL scan after 8 AM */
    public void sendLateAlert(Student student, LocalDateTime arrivalTime) {
        send(student,
             "⏰ " + student.getFullName() + " arrived late today — " + schoolName,
             buildCard(student, arrivalTime, null));
    }

    /** Student was absent (no scan by cutoff) — sent by the absence scheduler */
    public void sendAbsentAlert(Student student) {
        send(student,
             "⚠️ " + student.getFullName() + " was absent today — " + schoolName,
             buildAbsentCard(student));
    }

    /** Student has departed — sent immediately on DEPARTURE scan */
    public void sendDepartureAlert(Student student, LocalDateTime departureTime) {
        send(student,
             "🏠 " + student.getFullName() + " has left school — " + schoolName,
             buildCard(student, null, departureTime));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void send(Student student, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(emailFrom);
            helper.setTo(student.getParentEmail());
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent to {} for student {}", student.getParentEmail(), student.getStudentId());
        } catch (MessagingException e) {
            log.error("Failed to send email for student {}: {}", student.getStudentId(), e.getMessage());
            throw new RuntimeException("Email send failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generic card for ARRIVED / LATE / DEPARTED emails.
     * Pass arrivalTime OR departureTime — whichever applies; the other is null.
     */
    private String buildCard(Student student, LocalDateTime arrivalTime, LocalDateTime departureTime) {
        String dateStr = LocalDate.now().format(DATE_FMT);
        boolean isLate      = arrivalTime != null && departureTime == null
                              && arrivalTime.toLocalTime().isAfter(java.time.LocalTime.of(8, 0));
        boolean isDeparture = departureTime != null;

        String color, icon, headline, timeLabel, timeValue;

        if (isDeparture) {
            color     = "#81C784";   // green
            icon      = "🏠";
            headline  = "has <strong>LEFT school</strong>";
            timeLabel = "Departure Time";
            timeValue = departureTime.format(TIME_FMT);
        } else if (isLate) {
            color     = "#FFD54F";   // amber
            icon      = "⏰";
            headline  = "arrived <strong>LATE</strong>";
            timeLabel = "Arrival Time";
            timeValue = arrivalTime.format(TIME_FMT);
        } else {
            color     = "#64B5F6";   // blue
            icon      = "✅";
            headline  = "has <strong>ARRIVED</strong> safely";
            timeLabel = "Arrival Time";
            timeValue = arrivalTime.format(TIME_FMT);
        }

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
                      <strong>%s</strong> %s today.
                    </p>
                    <p style="font-size:13px;color:#64748B;margin:6px 0 0;">%s</p>
                  </div>
                  <table style="width:100%%;border-collapse:collapse;margin-bottom:28px;">
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Student</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Grade / Class</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Student ID</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;color:#64748B;font-size:13px;">%s</td>
                        <td style="padding:10px 0;font-weight:600;font-size:13px;">%s</td></tr>
                  </table>
                  <p style="font-size:13px;color:#64748B;line-height:1.6;">
                    If you have any questions, please contact the school office. This notification was sent automatically by the EduTrack attendance system.
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
                timeLabel, timeValue,
                schoolName
        );
    }

    private String buildAbsentCard(Student student) {
        String dateStr = LocalDate.now().format(DATE_FMT);
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
                  <div style="background:#FF8A8020;border-left:4px solid #FF8A80;border-radius:12px;padding:20px 24px;margin-bottom:28px;text-align:center;">
                    <div style="font-size:36px;">❌</div>
                    <p style="font-size:16px;color:#1E293B;margin:8px 0 0;">
                      <strong>%s</strong> was marked <strong>ABSENT</strong> today.
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
                    If your child was present today or you believe this is an error, please contact the school office immediately. This notification was sent automatically by the EduTrack attendance system.
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
                student.getFullName(),
                dateStr,
                student.getFullName(),
                student.getGrade(),
                student.getStudentId(),
                schoolName
        );
    }

    /**
     * Sent by the principal via POST /api/analytics/notify-high-risk
     * Warns a parent that their child's attendance has dropped below 80%.
     */
    public void sendAbsenceWarningToParent(Student student, long absences, long totalDays, double pct) {
        String subject = "📋 Attendance Warning: " + student.getFullName() + " — " + schoolName;
        String dateStr = LocalDate.now().format(DATE_FMT);

        String body = """
            <!DOCTYPE html>
            <html>
            <body style="margin:0;padding:0;font-family:Arial,sans-serif;background:#F0F9FF;">
              <div style="max-width:560px;margin:40px auto;background:white;border-radius:20px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                <div style="background:linear-gradient(135deg,#1E3A5F,#0F2240);padding:32px 40px;text-align:center;">
                  <div style="font-size:48px;margin-bottom:8px;">🏫</div>
                  <h1 style="color:white;font-size:22px;margin:0;">%s</h1>
                  <p style="color:rgba(255,255,255,0.6);font-size:13px;margin:6px 0 0;">Attendance Warning Notice</p>
                </div>
                <div style="padding:36px 40px;">
                  <div style="background:#FF8A8020;border-left:4px solid #FF8A80;border-radius:12px;padding:20px 24px;margin-bottom:28px;">
                    <p style="font-size:15px;color:#1E293B;margin:0;">
                      Dear Parent/Guardian,<br><br>
                      This is an official attendance warning from <strong>%s</strong>.<br><br>
                      Your child <strong>%s</strong> has been absent <strong>%d out of %d</strong> school days,
                      resulting in an attendance rate of <strong style="color:#EF4444;">%.1f%%</strong>.
                    </p>
                  </div>
                  <p style="font-size:13px;color:#64748B;line-height:1.8;">
                    The minimum required attendance rate is <strong>80%%</strong>. Continued absences may affect your child's academic progress and standing.<br><br>
                    Please contact the school office as soon as possible to discuss this matter.
                  </p>
                  <table style="width:100%%;border-collapse:collapse;margin:20px 0;">
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Student</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Grade</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;border-bottom:1px solid #E2EFF9;color:#64748B;font-size:13px;">Student ID</td>
                        <td style="padding:10px 0;border-bottom:1px solid #E2EFF9;font-weight:600;font-size:13px;">%s</td></tr>
                    <tr><td style="padding:10px 0;color:#64748B;font-size:13px;">Notice Date</td>
                        <td style="padding:10px 0;font-weight:600;font-size:13px;">%s</td></tr>
                  </table>
                </div>
                <div style="background:#F8FBFF;padding:20px 40px;text-align:center;border-top:1px solid #E2EFF9;">
                  <p style="font-size:12px;color:#94A3B8;margin:0;">© 2026 %s · Powered by EduTrack</p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                schoolName, schoolName,
                student.getFullName(), absences, totalDays, pct,
                student.getFullName(), student.getGrade(), student.getStudentId(), dateStr,
                schoolName
        );

        send(student, subject, body);
    }
}
