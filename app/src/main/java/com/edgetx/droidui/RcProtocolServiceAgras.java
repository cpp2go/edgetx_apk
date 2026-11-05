package com.edgetx.droidui;

/**
 * The same listener as {@link RcProtocolService}, in the process name of DJI's agriculture app.
 *
 * <p>Which DJI process the protocol library answers in is not documented: it follows the app the
 * remote normally runs, and that differs between the consumer, agriculture and industry builds.
 * All four are declared (see the manifest) and the first one that delivers wins - see
 * {@link RcProtocolInputs}. The class is empty on purpose: the base class is the whole listener.
 */
public final class RcProtocolServiceAgras extends RcProtocolService {
}
