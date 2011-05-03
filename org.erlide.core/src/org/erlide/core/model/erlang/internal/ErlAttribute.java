/*******************************************************************************
 * Copyright (c) 2004 Vlad Dumitrescu and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Vlad Dumitrescu
 *******************************************************************************/
package org.erlide.core.model.erlang.internal;

import org.erlide.core.model.erlang.IErlAttribute;
import org.erlide.core.model.root.api.IParent;
import org.erlide.core.model.root.internal.ErlMember;

import com.ericsson.otp.erlang.OtpErlangObject;

/**
 * @author Vlad Dumitrescu
 */
public class ErlAttribute extends ErlMember implements IErlAttribute {

    private final OtpErlangObject fValue;
    private final String fExtra;

    /**
     * @param parent
     * @param name
     */
    public ErlAttribute(final IParent parent, final String name,
            final OtpErlangObject value, final String extra) {
        super(parent, name);
        fValue = value;
        fExtra = extra;
    }

    /**
     * @see org.erlide.core.model.root.api.IErlElement#getKind()
     */
    public Kind getKind() {
        return Kind.ATTRIBUTE;
    }

    public OtpErlangObject getValue() {
        return fValue;
    }

    @Override
    public String toString() {
        String sval;
        if (fValue != null) {
            sval = ": " + fValue.toString(); // pp(fValue);
        } else if (fExtra != null) {
            sval = ": " + fExtra;
        } else {
            sval = "";
        }
        return getName() + sval;
    }
}