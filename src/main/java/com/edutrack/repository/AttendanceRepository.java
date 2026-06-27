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

    List<AttendanceRecord> findByParentNotifiedFalseAndStatus(AttendanceRecord.Status status);

    @Query("SELECT COUNT(a) FROM AttendanceRecord a WHERE a.attendanceDate = :date AND a.status = :status AND a.student.active = true")
    long countByDateAndStatusAndActiveStudent(@Param("date") LocalDate date, @Param("status") AttendanceRecord.Status status);

    // ── Student profile queries ──────────────────────────────────────────────

    /** All records for one student in a date range, ordered oldest-first */
    @Query("""
        SELECT a FROM AttendanceRecord a
        WHERE a.student = :student
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
        ORDER BY a.attendanceDate ASC
        """)
    List<AttendanceRecord> findAllForStudentInRange(
            @Param("student")  Student student,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /** Count by status for one student in a date range */
    @Query("""
        SELECT a.status, COUNT(a)
        FROM AttendanceRecord a
        WHERE a.student = :student
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
        GROUP BY a.status
        """)
    List<Object[]> countByStatusForStudent(
            @Param("student")  Student student,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    // ── School-wide / grade queries ──────────────────────────────────────────

    /**
     * Returns every active student alongside their ABSENT count since a given date.
     * Students with zero absences are included (LEFT JOIN) so the caller can
     * compute a correct attendance percentage using actual school days.
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
    List<Object[]> getDailyTrend(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    @Query("""
        SELECT a.student.grade,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.PRESENT THEN 1 ELSE 0 END) * 100.0 / COUNT(a) as pct
        FROM AttendanceRecord a
        WHERE a.attendanceDate = :date
        GROUP BY a.student.grade
        ORDER BY a.student.grade
        """)
    List<Object[]> getClassAttendanceForDate(@Param("date") LocalDate date);

    /** Grade-level trend for a date range */
    @Query("""
        SELECT a.student.grade,
               a.attendanceDate,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.PRESENT THEN 1 ELSE 0 END) as present,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.LATE    THEN 1 ELSE 0 END) as late,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.ABSENT  THEN 1 ELSE 0 END) as absent
        FROM AttendanceRecord a
        WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
        GROUP BY a.student.grade, a.attendanceDate
        ORDER BY a.student.grade, a.attendanceDate
        """)
    List<Object[]> getGradeTrendInRange(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /**
     * Perfect attendance students: active students with zero ABSENT or LATE
     * records in the given range.
     * NOTE: Students with NO records at all (never scanned) are intentionally
     * excluded by checking s.id NOT IN the attendance records — a student with
     * no records has unknown attendance, not perfect attendance.
     */
    @Query("""
        SELECT s FROM Student s
        WHERE s.active = true
          AND s.id IN (
              SELECT DISTINCT a.student.id FROM AttendanceRecord a
              WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
          )
          AND s.id NOT IN (
              SELECT a.student.id FROM AttendanceRecord a
              WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
                AND a.status IN (
                    com.edutrack.model.AttendanceRecord.Status.ABSENT,
                    com.edutrack.model.AttendanceRecord.Status.LATE
                )
          )
        ORDER BY s.grade, s.fullName
        """)
    List<Student> findPerfectAttendanceStudents(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /** Most frequently late students in a range */
    @Query("""
        SELECT a.student, COUNT(a) as lateCount
        FROM AttendanceRecord a
        WHERE a.status = com.edutrack.model.AttendanceRecord.Status.LATE
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
          AND a.student.active = true
        GROUP BY a.student
        ORDER BY lateCount DESC
        """)
    List<Object[]> findMostLateStudents(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /**
     * Average arrival time per grade.
     * BUG FIX: Removed HOUR() and MINUTE() which are MySQL-specific and fail on PostgreSQL.
     * We now fetch all records with an arrivalTime and compute the average Java-side
     * in AnalyticsController.avgArrivalTime().
     */
    @Query("""
        SELECT a FROM AttendanceRecord a
        WHERE a.arrivalTime IS NOT NULL
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
          AND a.student.active = true
        ORDER BY a.student.grade
        """)
    List<AttendanceRecord> findRecordsWithArrivalTime(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);
}
