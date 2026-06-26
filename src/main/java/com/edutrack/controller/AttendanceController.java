package com.edutrack.controller;

import com.edutrack.dto.ScanRequest;
import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import com.edutrack.model.User;
import com.edutrack.repository.AttendanceRepository;
import com.edutrack.repository.StudentRepository;
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
    private final StudentRepository studentRepo;
    private final EmailService emailService;

    public AttendanceController(AttendanceRepository attendanceRepo,
                                StudentRepository studentRepo,
                                EmailService emailService) {
        this.attendanceRepo = attendanceRepo;
        this.studentRepo    = studentRepo;
        this.emailService   = emailService;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'SCANNER')")
    public ResponseEntity<?> scan(@Valid @RequestBody ScanRequest body,
                                  Authentication auth) {

        String qrCode   = body.qrCode();
        String scanType = body.scanType() != null ? body.scanType() : "ARRIVAL";

        Student student = studentRepo.findByQrCode(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown QR code"));

        if (!student.isActive()) {
            throw new IllegalArgumentException("This student's QR code is no longer active");
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        User scannedBy = (User) auth.getPrincipal();

        AttendanceRecord record = attendanceRepo
                .findByStudentAndAttendanceDate(student, today)
                .orElseGet(() -> AttendanceRecord.builder()
                        .student(student)
                        .attendanceDate(today)
                        .status(AttendanceRecord.Status.ABSENT)
                        .scannedBy(scannedBy)
                        .build());

        if ("ARRIVAL".equalsIgnoreCase(scanType)) {
            record.setArrivalTime(now);
            record.setStatus(now.toLocalTime().isAfter(LATE_CUTOFF)
                    ? AttendanceRecord.Status.LATE
                    : AttendanceRecord.Status.PRESENT);
        } else {
            record.setDepartureTime(now);
            if (record.getStatus() == AttendanceRecord.Status.ABSENT) {
                record.setStatus(AttendanceRecord.Status.PRESENT);
            }
        }

        attendanceRepo.save(record);

        if (!record.isParentNotified() &&
                (record.getStatus() == AttendanceRecord.Status.ABSENT ||
                 record.getStatus() == AttendanceRecord.Status.LATE)) {
            try {
                emailService.sendAttendanceAlert(student, record.getStatus());
                record.setParentNotified(true);
                attendanceRepo.save(record);
            } catch (Exception e) {
                // Email failed — leave parentNotified=false so it can retry
            }
        }

        // FIX: Return a plain Map, NOT the AttendanceRecord entity.
        // The entity has scannedBy (User implements UserDetails with an "authorities"
        // collection) which caused Jackson serialization 500 errors on this endpoint.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Attendance recorded");
        response.put("student", student.getFullName());
        response.put("status",  record.getStatus().name());
        response.put("time",    now.toString());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/attendance/today
     *
     * FIX: Was returning AttendanceRecord entities directly. Those include a scannedBy
     * User field which implements UserDetails — Jackson tried to serialize its
     * "authorities" list and threw a 500. Now returns safe DTOs instead.
     */
    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<List<Map<String, Object>>> today() {
        List<AttendanceRecord> records = attendanceRepo.findByAttendanceDate(LocalDate.now());

        List<Map<String, Object>> result = records.stream().map(r -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", r.getId());
            entry.put("attendanceDate", r.getAttendanceDate().toString());
            entry.put("status", r.getStatus().name());
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
                            entry.put("id", r.getId());
                            entry.put("attendanceDate", r.getAttendanceDate().toString());
                            entry.put("status", r.getStatus().name());
                            entry.put("arrivalTime",   r.getArrivalTime()   != null ? r.getArrivalTime().toString()   : null);
                            entry.put("departureTime", r.getDepartureTime() != null ? r.getDepartureTime().toString() : null);
                            return entry;
                        }).toList();

        return ResponseEntity.ok(result);
    }
}
