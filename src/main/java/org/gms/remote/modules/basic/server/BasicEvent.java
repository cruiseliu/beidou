package org.gms.remote.modules.basic.server;

import org.gms.remote.ServerEvent;

public sealed interface BasicEvent extends ServerEvent permits
    InitializeEvent,
    UpdateJobEvent,
    UpdateLevelEvent,
    UpdateExpEvent,
    UnlockActionsEvent
{ }
