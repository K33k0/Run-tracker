# RunZone changelog

## v1.2.4

- Ask for missing location, Nearby devices, and notification permissions when RunZone opens, where supported by Android. RunZone accepts approximate location if you choose it.
- Match Smashrun’s public-client callback to the active sign-in even when the response omits OAuth state, as long as the callback includes the matching attempt identifier.
- Show specific messages for expired, declined, or mismatched Smashrun sign-ins and missing upload permission.
