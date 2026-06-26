package com.edutrack.repository;

import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findByStudentAndAttendanceDate(Student student, LocalDate date);

    List<AttendanceRecord> findByAttendanceDate(LocalDate date);

    List<AttendanceRecord> findByStudent(Student student);

    List<AttendanceRecord> findByStudentAndAttendanceDateBetween(Student student, LocalDate fromDate, LocalDate toDate);

    List<AttendanceRecord> findByAttendanceDateBetween(LocalDate fromDate, LocalDate toDate);

    /**
     * Counts attendance records for ACTIVE students only, for a given date and status.
     * Prevents soft-deleted students from corrupting the absent count.
     */
    @Query("SELECT COUNT(a) FROM AttendanceRecord a WHERE a.attendanceDate = :date AND a.status = :status AND a.student.active = true")
    long countByDateAndStatusAndActiveStudent(@Param("date") LocalDate date, @Param("status") AttendanceRecord.Status status);

    /**
     * LEFT JOIN from Student so students with ZERO records (never scanned) appear
     * in the high-risk report with absences = 0 (meaning they were never present).
     */
    @Query("""
        SELECT s, COUNT(a) as absences
        FROM Student s
        LEFT JOIN AttendanceRecord a
          ON a.student = s
          AND a.status = com.edutrack.model.AttendanceRecord.Status.ABSENT
          AND a.attendanceDate >= :since
        WHERE s.active = true
        GROUP BY s
        ORDER BY absences DESC
        """)
    List<Object[]> findHighRiskStudentsIncludingUnscanned(@Param("since") LocalDate since);

    /**
     * Daily attendance breakdown — uses :fromDate/:toDate to avoid JPQL reserved keyword 'from'.
     */
    @Query("""
        SELECT a.attendanceDate,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.PRESENT THEN 1 ELSE 0 END) as present,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.LATE    THEN 1 ELSE 0 END) as late,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.ABSENT  THEN 1 ELSE 0 END) as absent
        FROM AttendanceRecord a
        WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
        GROUP BY a.attendanceDate
        ORDER BY a.attendanceDate
        """)
    List<Object[]> getDailyTrend(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    @Query("""
        SELECT a.student.grade,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.PRESENT THEN 1 ELSE 0 END) * 100.0 / COUNT(a) as pct
        FROM AttendanceRecord a
        WHERE a.attendanceDate = :date
        GROUP BY a.student.grade
        ORDER BY a.student.grade
        """)
    List<Object[]> getClassAttendanceForDate(@Param("date") LocalDate date);

    List<AttendanceRecord> findByParentNotifiedFalseAndStatus(AttendanceRecord.Status status);
}
