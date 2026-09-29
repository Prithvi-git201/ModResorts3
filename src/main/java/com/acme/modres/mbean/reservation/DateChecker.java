package com.acme.modres.mbean.reservation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.acme.modres.Constants;

/**
 * Runnable task that checks whether the selected date falls within any existing
 * reservation period.
 *
 * <p>Cloud-readiness fix (cr-java-0111 – Clock/Time Dependencies):
 * Date comparisons previously used {@link java.util.Date} objects obtained via
 * the server-timezone-sensitive {@link java.text.SimpleDateFormat}.  These have
 * been replaced with {@link java.time.LocalDate} and
 * {@link java.time.format.DateTimeFormatter}, which are timezone-agnostic and
 * produce consistent results regardless of the JVM timezone configured on each
 * cloud node or container.
 *
 * <p>Scheduling note: if this {@link Runnable} was previously submitted to a
 * {@code java.util.Timer} for periodic execution, that timer should be replaced
 * with Azure Service Bus Scheduled Messages so that task scheduling is
 * distributed, durable, and timezone-agnostic across cloud regions.
 */
public class DateChecker implements Runnable {
  ReservationCheckerData data;
  List<Reservation> reservations;

  public DateChecker(ReservationCheckerData data) {
    this.data = data;
    this.reservations = data.getReservationList().getReservations();
  }

  public void run() {
    // cr-java-0111: Use DateTimeFormatter + LocalDate instead of
    // SimpleDateFormat + java.util.Date to avoid server-local timezone
    // dependency in distributed cloud environments.
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Constants.DATA_FORMAT);

    for (int i = 0; i < reservations.size(); i++) {
      Reservation reservation = reservations.get(i);
      LocalDate selectedDate = data.getSelectedDate();

      try {
        LocalDate fromDate = LocalDate.parse(reservation.getFromDate(), formatter);
        LocalDate toDate   = LocalDate.parse(reservation.getToDate(),   formatter);
        if (selectedDate.isAfter(fromDate) && selectedDate.isBefore(toDate)) {
          data.setAvailablility(false);
          return;
        }
      } catch (DateTimeParseException ex) {
        ex.printStackTrace();
      }
    }
    data.setAvailablility(true);
  }
}
