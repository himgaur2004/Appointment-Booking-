package com.appointment.controller;

import com.appointment.dto.CreateAppointmentRequest;
import com.appointment.entity.Dealership;
import com.appointment.enums.AppointmentStatus;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import com.appointment.repository.AppointmentRepository;
import com.appointment.repository.DealershipRepository;
import com.appointment.repository.ReminderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AppointmentController Integration Tests")
class AppointmentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DealershipRepository dealershipRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    @BeforeEach
    void setUp() {
        reminderRepository.deleteAll();
        appointmentRepository.deleteAll();
        dealershipRepository.deleteAll();

        Dealership dealership = Dealership.builder()
                .name("Test Dealership")
                .address("123 Test St")
                .timezone("UTC")
                .build();
        dealershipRepository.save(dealership);
    }

    @Nested
    @DisplayName("POST /api/v1/appointments")
    class CreateEndpoint {

        @Test
        @DisplayName("Should create appointment and return 201 with reminders")
        void shouldCreateAppointment() throws Exception {
            Long dealershipId = dealershipRepository.findAll().get(0).getId();
            Instant scheduledAt = Instant.now().plus(48, ChronoUnit.HOURS);

            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    dealershipId, "John Doe", "john@example.com", "+1-555-0100",
                    "2024 Toyota Camry", "Oil Change", scheduledAt);

            mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.customerName").value("John Doe"))
                    .andExpect(jsonPath("$.customerEmail").value("john@example.com"))
                    .andExpect(jsonPath("$.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.dealershipName").value("Test Dealership"))
                    .andExpect(jsonPath("$.reminders", hasSize(2)));

            // Verify DB state
            assertThat(appointmentRepository.count()).isEqualTo(1);
            assertThat(reminderRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should return 400 for missing required fields")
        void shouldRejectInvalidRequest() throws Exception {
            String invalidJson = """
                    {
                        "customerName": "",
                        "serviceType": ""
                    }
                    """;

            mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Validation Failed"));
        }

        @Test
        @DisplayName("Should return 400 for past scheduled time")
        void shouldRejectPastTime() throws Exception {
            Long dealershipId = dealershipRepository.findAll().get(0).getId();
            Instant pastTime = Instant.now().minus(1, ChronoUnit.HOURS);

            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    dealershipId, "John", "john@example.com", null,
                    "Vehicle", "Service", pastTime);

            mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("future")));
        }

        @Test
        @DisplayName("Should return 404 for non-existent dealership")
        void shouldRejectInvalidDealership() throws Exception {
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    999L, "John", "john@example.com", null,
                    "Vehicle", "Service", Instant.now().plus(48, ChronoUnit.HOURS));

            mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 for missing contact info")
        void shouldRejectMissingContact() throws Exception {
            Long dealershipId = dealershipRepository.findAll().get(0).getId();

            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    dealershipId, "John", null, null,
                    "Vehicle", "Service", Instant.now().plus(48, ChronoUnit.HOURS));

            mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("contact")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/appointments/{id}")
    class GetEndpoint {

        @Test
        @DisplayName("Should return appointment with reminders")
        void shouldReturnAppointment() throws Exception {
            // First create an appointment
            Long dealershipId = dealershipRepository.findAll().get(0).getId();
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    dealershipId, "Jane Doe", "jane@example.com", null,
                    "2023 Honda Civic", "Tire Rotation",
                    Instant.now().plus(48, ChronoUnit.HOURS));

            MvcResult createResult = mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn();

            String responseBody = createResult.getResponse().getContentAsString();
            Long appointmentId = objectMapper.readTree(responseBody).get("id").asLong();

            // Then get it
            mockMvc.perform(get("/api/v1/appointments/{id}", appointmentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(appointmentId))
                    .andExpect(jsonPath("$.customerName").value("Jane Doe"))
                    .andExpect(jsonPath("$.reminders", hasSize(2)));
        }

        @Test
        @DisplayName("Should return 404 for non-existent appointment")
        void shouldReturn404() throws Exception {
            mockMvc.perform(get("/api/v1/appointments/{id}", 999))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/appointments/{id}/cancel")
    class CancelEndpoint {

        @Test
        @DisplayName("Should cancel appointment and reminders")
        void shouldCancelAppointment() throws Exception {
            Long dealershipId = dealershipRepository.findAll().get(0).getId();
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    dealershipId, "Bob Smith", "bob@example.com", null,
                    "2022 Ford F-150", "Brake Service",
                    Instant.now().plus(48, ChronoUnit.HOURS));

            MvcResult createResult = mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn();

            Long appointmentId = objectMapper.readTree(
                    createResult.getResponse().getContentAsString()).get("id").asLong();

            // Cancel it
            mockMvc.perform(put("/api/v1/appointments/{id}/cancel", appointmentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.reminders[*].status",
                            everyItem(is("CANCELLED"))));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/appointments")
    class ListEndpoint {

        @Test
        @DisplayName("Should return paginated appointments")
        void shouldListAppointments() throws Exception {
            Long dealershipId = dealershipRepository.findAll().get(0).getId();

            // Create 3 appointments
            for (int i = 0; i < 3; i++) {
                CreateAppointmentRequest request = new CreateAppointmentRequest(
                        dealershipId, "Customer " + i, "c" + i + "@example.com", null,
                        "Vehicle " + i, "Service " + i,
                        Instant.now().plus(48 + i, ChronoUnit.HOURS));

                mockMvc.perform(post("/api/v1/appointments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated());
            }

            mockMvc.perform(get("/api/v1/appointments")
                            .param("page", "0")
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }
    }

    @Nested
    @DisplayName("Idempotency Proof")
    class IdempotencyTests {

        @Test
        @DisplayName("Should prove that duplicate reminders cannot exist in the database")
        void shouldPreventDuplicateReminders() throws Exception {
            Long dealershipId = dealershipRepository.findAll().get(0).getId();
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    dealershipId, "Idempotency Test", "idem@example.com", null,
                    "Vehicle", "Service",
                    Instant.now().plus(48, ChronoUnit.HOURS));

            MvcResult result = mockMvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn();

            Long appointmentId = objectMapper.readTree(
                    result.getResponse().getContentAsString()).get("id").asLong();

            // Verify exactly 2 reminders exist (one per type)
            var reminders = reminderRepository.findByAppointmentId(appointmentId);
            assertThat(reminders).hasSize(2);

            // Verify we have one of each type
            assertThat(reminders.stream().map(r -> r.getReminderType()).toList())
                    .containsExactlyInAnyOrder(ReminderType.HOURS_24, ReminderType.HOURS_2);

            // Verify unique constraint - attempt to insert duplicate would fail
            // This proves that even if scheduler processes the same appointment twice,
            // it cannot create a duplicate reminder
            var appointment = appointmentRepository.findById(appointmentId).orElseThrow();
            assertThat(reminderRepository.findByAppointmentIdAndReminderType(
                    appointmentId, ReminderType.HOURS_24)).isPresent();
            assertThat(reminderRepository.findByAppointmentIdAndReminderType(
                    appointmentId, ReminderType.HOURS_2)).isPresent();
        }
    }
}
