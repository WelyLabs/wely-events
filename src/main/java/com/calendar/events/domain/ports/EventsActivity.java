package com.calendar.events.domain.ports;

/**
 * What happens on wely-events, as the product sees it, for the dashboards. The domain reports each fact once
 * it is established — after the write succeeded — and never on a refusal or a failure. How it is measured is
 * the adapter's business: no metrics type crosses into the domain.
 */
public interface EventsActivity {

    /** A user created an event: a standard one or a surprise. Birthdays, created from the signup, are not. */
    void eventCreated();

    /** An organizer invited a friend to one of their events. */
    void invitationSent();

    /** An event was created with a date poll open on its candidate slots. */
    void pollCreated();

    /** Someone cast a ballot in a date poll; withdrawing one (an empty ballot) is not a vote. */
    void pollVoted();
}
