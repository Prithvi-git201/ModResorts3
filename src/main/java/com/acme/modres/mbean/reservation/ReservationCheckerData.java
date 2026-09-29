package com.acme.modres.mbean.reservation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.acme.modres.Constants;

/**
 * Holds the state for a single availability-check request.
 *
 * <p>Cloud-readiness fix (cr-java-0111 – Clock/Time Dependencies):
 * The {@code selectedDate} field previously used {@link java.util.Date}, which
 * relies on the server-local JVM timezone and is therefore non-deterministic in
 * distributed / multi-region cloud deployments.  It has been replaced with
 * {@link java.time.LocalDate}, which is timezone-agnostic and represents a
 * calendar date without any time-of-day or timezone component.  Parsing is
 * performed with {@link java.time.format.DateTimeFormatter} (UTC-safe) instead
 * of the deprecated, non-thread-safe {@link java.text.SimpleDateFormat}.
 *
 * <p>Scheduling note: any timer-based or periodic availability checks that were
 * previously driven by {@code java.util.Timer} should be migrated to Azure
 * Service Bus Scheduled Messages so that task execution is distributed,
 * timezone-agnostic, and resilient across cloud regions.
 */
public class ReservationCheckerData {
  private ReservationList reservations;

  /**
   * The date selected by the caller, stored as a timezone-agnostic
   * {@link LocalDate} (replaces the former {@code java.util.Date} field).
   */
  private LocalDate selectedDate;

  private boolean available; // changed from Boolean to boolean

  public ReservationCheckerData(ReservationList reservations) {
    this.reservations = reservations;
    this.available = true;
  }

  public ReservationList getReservationList() {
    return reservations;
  }

  /**
   * Returns the selected date as a timezone-agnostic {@link LocalDate}.
   *
   * @return the parsed {@link LocalDate}, or {@code null} if not yet set
   */
  public LocalDate getSelectedDate() {
    return selectedDate;
  }

  /**
   * Parses {@code dateStr} using the application date format and stores the
   * result as a {@link LocalDate}.  Uses {@link DateTimeFormatter} instead of
   * the server-timezone-sensitive {@link java.text.SimpleDateFormat}.
   *
   * @param dateStr the date string to parse
   * @return {@code true} if parsing succeeded; {@code false} otherwise
   */
  public boolean setSelectedDate(String dateStr) {
    try {
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Constants.DATA_FORMAT);
      selectedDate = LocalDate.parse(dateStr, formatter);
    } catch (DateTimeParseException e) {
      return false;
    }
    return true;
  }

  public boolean isAvailible() {
    return available;
  }

  public void setAvailablility(boolean available) { // fix parameter type
    this.available = available;
  }
}
