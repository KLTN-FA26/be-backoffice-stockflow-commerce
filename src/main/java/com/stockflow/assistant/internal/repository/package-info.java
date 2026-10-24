/**
 * Persistence of conversations: Spring Data repositories and the adapter implementing the domain's
 * {@code ConversationRepository} port. The turns are stored as JSON exactly as the model saw them; this is the only
 * layer that knows their JSON shape.
 */
package com.stockflow.assistant.internal.repository;
