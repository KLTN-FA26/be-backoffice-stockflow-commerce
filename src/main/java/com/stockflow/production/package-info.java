/**
 * Sample approval and print production. Persistence and runtime wiring await SCRUM-422;
 * the domain and application ports are deliberately usable without a database.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"customer :: api", "design :: api"})
package com.stockflow.production;
