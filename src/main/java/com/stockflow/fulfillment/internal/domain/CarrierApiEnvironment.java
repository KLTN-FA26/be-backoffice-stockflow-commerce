package com.stockflow.fulfillment.internal.domain;

/**
 * Which of a carrier's environments its API calls go to (kltn-docs 09 §3 "môi trường staging / production"). A key is
 * issued per environment, so the two are always saved together.
 */
public enum CarrierApiEnvironment { STAGING, PRODUCTION }
