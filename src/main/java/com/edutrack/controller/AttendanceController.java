package com.edutrack.controller;

import com.edutrack.dto.ScanRequest;
import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import com.edutrack.model.User;
import com.edutrack.repository.AttendanceRepository;
import com.edutrack.repository.StudentRepository;
import com.edutrack.service.CalendarService;
import com.edutrack.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/attendance")
public class AttendanceController {

    private static final LocalTime LATE_CUTOFF = LocalTime.of(8, 0);

    private final AttendanceRepository attendanceRepo;
    private final StudentRepository    studentRepo;
    private final EmailService         emailService;
    private final CalendarService      calendarService;

    public AttendanceController(AttendanceRepository attendanceRepo,
                                StudentRepository studentRepo,
                                EmailService emailService,
                                CalendarService calendarService) {
        this.attendanceRepo  = attendanceRepo;
        this.studentRepo     = studentRepo;
        this.emailService    = emailService;
        this.calendarService = calendarService;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'SCANNER')")
    public ResponseEntity<?> scan(@Valid @RequestBody ScanRequest body,
                                  Authentication auth) {

        // Block scanning on weekends and special holidays
        if (!calendarService.isSchoolDay(LocalDate.now())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Today is not a school day. No attendance recorded."));
        }

        String qrCode   = body.qrCode();
        String scanType = body.scanType() != null ? body.scanType().toUpperCase() : "ARRIVAL";

        Student student = studentRepo.findByQrCode(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown QR code"));

        if (!student.isActive()) {
            throw new IllegalArgumentException("This student's QR code is no longer active");
        }

        LocalDate today   = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        User scannedBy    = (User) auth.getPrincipal();

        // Load existing record for today, or create a new skeleton (not saved yet)
        AttendanceRecord record = attendanceRepo
                .findByStudentAndAttendanceDate(student, today)
                .orElseGet(() -> AttendanceRecord.builder()
                        .student(student)
                        .attendanceDate(today)
                        .status(AttendanceRecord.Status.ABSENT)
                        .scannedBy(scannedBy)
                        .parentNotified(false)
                        .parentDepartureNotified(false)
                        .build());

        if ("ARRIVAL".equals(scanType)) {
            // ── ARRIVAL scan ─────────────────────────────────────────────────
            // Only update arrivalTime if this is the FIRST arrival scan.
            // Prevents a re-scan from overwriting the original arrival time.
            if (record.getArrivalTime() == null) {
                boolean isLate = now.toLocalTime().isAfter(LATE_CUTOFF);
                record.setArrivalTime(now);
                record.setStatus(isLate
                        ? AttendanceRecord.Status.LATE
                        : AttendanceRecord.Status.PRESENT);
                attendanceRepo.save(record);

                // Send arrival or late email once
                if (!record.isParentNotified()) {
                    try {
                        if (isLate) {
                            emailService.sendLateAlert(student, now);
                        } else {
                            emailService.sendArrivalAlert(student, now);
                        }
                        record.setParentNotified(true);
                        attendanceRepo.save(record);
                    } catch (Exception e) {
                        // Email failed — parentNotified stays false, will retry on next arrival scan
                    }
                }
            } else {
                // Already scanned in — just acknowledge, don't update time or resend email
                attendanceRepo.save(record); // ensure record persisted if new
            }

        } else if ("DEPARTURE".equals(scanType)) {
            // ── DEPARTURE scan ────────────────────────────────────────────────
            // Only update departureTime if no departure has been recorded yet.
            // Prevents re-scan from overwriting original departure time.
            if (record.getDepartureTime() == null) {
                record.setDepartureTime(now);

                // Edge case: student scanned out without ever scanning in
                // (e.g. gate operator missed arrival scan) — mark as PRESENT
                if (record.getStatus() == AttendanceRecord.Status.ABSENT) {
                    record.setStatus(AttendanceRecord.Status.PRESENT);
                }
                attendanceRepo.save(record);

                // Send departure email once
                if (!record.isParentDepartureNotified()) {
                    try {
                        emailService.sendDepartureAlert(student, now);
                        record.setParentDepartureNotified(true);
                        attendanceRepo.save(record);
                    } catch (Exception e) {
                        // Email failed — will retry on next departure scan
                    }
                }
            } else {
                // Already scanned out — just acknowledge
                attendanceRepo.save(record);
            }

        } else {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid scanType. Must be ARRIVAL or DEPARTURE."));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message",       "Attendance recorded");
        response.put("student",       student.getFullName());
        response.put("status",        record.getStatus().name());
        response.put("arrivalTime",   record.getArrivalTime()   != null ? record.getArrivalTime().toString()   : null);
        response.put("departureTime", record.getDepartureTime() != null ? record.getDepartureTime().toString() : null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<List<Map<String, Object>>> today() {
        List<AttendanceRecord> records = attendanceRepo.findByAttendanceDate(LocalDate.now());

        List<Map<String, Object>> result = records.stream().map(r -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",            r.getId());
            entry.put("attendanceDate", r.getAttendanceDate().toString());
            entry.put("status",        r.getStatus().name());
            entry.put("arrivalTime",   r.getArrivalTime()   != null ? r.getArrivalTime().toString()   : null);
            entry.put("departureTime", r.getDepartureTime() != null ? r.getDepartureTime().toString() : null);
            if (r.getStudent() != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("fullName",  r.getStudent().getFullName());
                s.put("grade",     r.getStudent().getGrade());
                s.put("studentId", r.getStudent().getStudentId());
                entry.put("student", s);
            }
            return entry;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/student/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<List<Map<String, Object>>> studentHistory(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        Student student = studentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        List<Map<String, Object>> result =
                attendanceRepo.findByStudentAndAttendanceDateBetween(student, fromDate, toDate)
                        .stream().map(r -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id",            r.getId());
                            entry.put("attendanceDate", r.getAttendanceDate().toString());
                            entry.put("status",        r.getStatus().name());
                            entry.put("arrivalTime",   r.getArrivalTime()   != null ? r.getArrivalTime().toString()   : null);
                            entry.put("departureTime", r.getDepartureTime() != null ? r.getDepartureTime().toString() : null);
                            return entry;
                        }).toList();

        return ResponseEntity.ok(result);
    }
}
