package com.edutrack.controller;

import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import com.edutrack.repository.AttendanceRepository;
import com.edutrack.repository.StudentRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/analytics")
@PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
public class AnalyticsController {

    private final AttendanceRepository attendanceRepo;
    private final StudentRepository studentRepo;

    public AnalyticsController(AttendanceRepository attendanceRepo, StudentRepository studentRepo) {
        this.attendanceRepo = attendanceRepo;
        this.studentRepo    = studentRepo;
    }

    /**
     * GET /api/analytics/summary
     * Today's headline stats for the dashboard.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        LocalDate today = LocalDate.now();
        long total   = studentRepo.findByActiveTrue().size();

        long present = attendanceRepo.countByDateAndStatusAndActiveStudent(today, AttendanceRecord.Status.PRESENT);
        long late    = attendanceRepo.countByDateAndStatusAndActiveStudent(today, AttendanceRecord.Status.LATE);
        long absent  = total - present - late;

        double pct = total > 0 ? ((present + late) * 100.0 / total) : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalStudents",  total);
        result.put("presentToday",   present);
        result.put("lateToday",      late);
        result.put("absentToday",    Math.max(absent, 0));
        result.put("attendancePct",  Math.round(pct * 10.0) / 10.0);
        result.put("date",           today.toString());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analytics/trend?days=10
     * Daily attendance % for the past N school days (exactly N days).
     */
    @GetMapping("/trend")
    public ResponseEntity<List<Map<String, Object>>> trend(
            @RequestParam(defaultValue = "10") int days) {

        LocalDate toDate   = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(days - 1); // inclusive: days=10 → exactly 10 rows

        List<Object[]> rows = attendanceRepo.getDailyTrend(fromDate, toDate);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            LocalDate date   = (LocalDate) row[0];
            long presentDay  = ((Number) row[1]).longValue();
            long lateDay     = ((Number) row[2]).longValue();
            long absentDay   = ((Number) row[3]).longValue();
            long totalDay    = presentDay + lateDay + absentDay;
            double pct       = totalDay > 0 ? ((presentDay + lateDay) * 100.0 / totalDay) : 0;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date",    date.toString());
            entry.put("present", presentDay);
            entry.put("late",    lateDay);
            entry.put("absent",  absentDay);
            entry.put("pct",     Math.round(pct * 10.0) / 10.0);
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analytics/class-comparison
     * Today's attendance % per grade/class.
     */
    @GetMapping("/class-comparison")
    public ResponseEntity<List<Map<String, Object>>> classComparison() {
        List<Object[]> rows = attendanceRepo.getClassAttendanceForDate(LocalDate.now());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("grade", row[0]);
            entry.put("pct",   Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0);
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analytics/high-risk?since=yyyy-MM-dd
     * Students below 80% attendance. Uses LEFT JOIN so never-scanned students are included.
     */
    @GetMapping("/high-risk")
    public ResponseEntity<List<Map<String, Object>>> highRisk(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate since) {

        long totalDays = since.until(LocalDate.now()).getDays() + 1; // inclusive

        List<Object[]> rows = attendanceRepo.findHighRiskStudentsIncludingUnscanned(since);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Student s     = (Student) row[0];
            long absences = ((Number) row[1]).longValue();
            double pct    = totalDays > 0 ? ((totalDays - absences) * 100.0 / totalDays) : 100;

            if (pct < 80) {
                // Use LinkedHashMap to safely support null values (Map.of() throws NPE on nulls)
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("studentId",     s.getStudentId());
                entry.put("name",          s.getFullName());
                entry.put("grade",         s.getGrade());
                entry.put("absences",      absences);
                entry.put("attendancePct", Math.round(pct * 10.0) / 10.0);
                entry.put("parentEmail",   s.getParentEmail() != null ? s.getParentEmail() : "");
                result.add(entry);
            }
        }
        return ResponseEntity.ok(result);
    }
}
