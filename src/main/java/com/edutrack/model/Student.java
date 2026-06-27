package com.edutrack.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "students")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Student ID is required")
    @Size(max = 50, message = "Student ID must be 50 characters or less")
    @Column(nullable = false, unique = true)
    private String studentId;          // e.g. "MC2024-0042"

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must be 100 characters or less")
    @Column(nullable = false)
    private String fullName;

    @NotBlank(message = "Grade is required")
    @Size(max = 20, message = "Grade must be 20 characters or less")
    @Column(nullable = false)
    private String grade;              // e.g. "10A"

    @NotBlank(message = "Parent email is required")
    @Email(message = "Parent email must be a valid email address")
    @Column(nullable = false)
    private String parentEmail;

    @Size(max = 20, message = "Parent phone must be 20 characters or less")
    private String parentPhone;

    @Column(nullable = false)
    private String qrCode;             // unique token used in QR image (set by server)

    private String qrImageBase64;      // plain Base64 PNG (no data-URI prefix)

    private LocalDate dateOfBirth;

    @Column(nullable = false)
    private boolean active = true;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }
}
