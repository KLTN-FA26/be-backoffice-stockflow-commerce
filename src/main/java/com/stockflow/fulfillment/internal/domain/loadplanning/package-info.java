/**
 * <b>The 3D load planner</b> of full-truckload shipping (kltn-docs 09 §4.2, BR-10, BR-11, BR-12; SCRUM-391): which
 * vehicles, how many of each type, and where every carton sits inside them.
 *
 * <p>The public surface is {@link com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner} (request in,
 * plan out) and {@link com.stockflow.fulfillment.internal.domain.loadplanning.PlacementValidator} (the hard rules,
 * which a manual move on the 3D model must pass - BR-11). Everything else is the search: a port of the lead's earlier
 * Python kit solver ({@code solver_3d_kit.py} with the parts of {@code solver_3d.py} and {@code fill_ems.py} it uses),
 * pure Java with no state between calls and no persistence.</p>
 *
 * <p>Coordinates are integer millimetres with the origin at the inner-left floor corner by the front wall: {@code x}
 * runs across the width, {@code y} up, {@code z} along the length toward the door. The 3D view uses the same frame.</p>
 */
package com.stockflow.fulfillment.internal.domain.loadplanning;
