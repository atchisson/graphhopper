#!/usr/bin/env pwsh
# register_task.ps1
# Enregistre la tâche planifiée Windows pour update_data.ps1.
# À exécuter une seule fois en tant qu'administrateur.
#
# Usage :
#   powershell -ExecutionPolicy Bypass -File scripts\register_task.ps1

$TaskName   = "GraphHopper - Update Data"
$ScriptPath = Join-Path $PSScriptRoot "update_data.ps1"
$RepoRoot   = Split-Path $PSScriptRoot -Parent

$action  = New-ScheduledTaskAction `
    -Execute    "powershell.exe" `
    -Argument   "-NonInteractive -ExecutionPolicy Bypass -File `"$ScriptPath`"" `
    -WorkingDirectory $RepoRoot

# Tous les jours à 3h du matin
$trigger = New-ScheduledTaskTrigger -Daily -At "03:00"

$settings = New-ScheduledTaskSettingsSet `
    -ExecutionTimeLimit (New-TimeSpan -Hours 4) `
    -StartWhenAvailable `
    -RunOnlyIfNetworkAvailable

# Tourne avec le compte courant, mot de passe demandé à l'enregistrement
Register-ScheduledTask `
    -TaskName   $TaskName `
    -Action     $action `
    -Trigger    $trigger `
    -Settings   $settings `
    -RunLevel   Highest `
    -Force

Write-Host "Tâche '$TaskName' enregistrée."
Write-Host "Pour tester manuellement : Start-ScheduledTask -TaskName '$TaskName'"
