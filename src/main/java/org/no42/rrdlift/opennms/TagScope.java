/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

/**
 * Where the value of a meta-tag expression comes from.
 * NODE to RESOURCE are ordered from widest to narrowest; CONSTANT has no context; UNCLASSIFIED could not be parsed.
 */
public enum TagScope { CONSTANT, NODE, INTERFACE, METADATA, SERVICE, RESOURCE, UNCLASSIFIED }
