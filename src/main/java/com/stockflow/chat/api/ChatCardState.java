package com.stockflow.chat.api;

/** Where a card's action stands; the document's own state is in its payload (16 BR-03). */
public enum ChatCardState { OPEN, ACTED, SUPERSEDED, EXPIRED, CANCELLED, CLOSED }
