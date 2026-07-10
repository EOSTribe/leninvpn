#include "subscriptionStatusController.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>

#include "amnezia_application.h"
#include "containers/containers_defs.h"
#include "protocols/protocols_defs.h"

using namespace amnezia;

namespace
{
    constexpr char subscriptionStatusUrl[] = "https://api.leninvpn.org/client/subscription/status";
    constexpr char telegramSubscribeUrl[] = "https://t.me/leninvpnsubscription_bot";

    constexpr int refreshIntervalMsecs = 5 * 60 * 1000; // 5 minutes
    constexpr int requestTimeoutMsecs = 10 * 1000;
}

SubscriptionStatusController::SubscriptionStatusController(const QSharedPointer<ServersModel> &serversModel,
                                                           const std::shared_ptr<Settings> &settings, QObject *parent)
    : QObject(parent), m_serversModel(serversModel), m_settings(settings)
{
    connect(m_serversModel.get(), &ServersModel::defaultServerIndexChanged, this, [this]() { refreshStatus(); });
    connect(m_serversModel.get(), &ServersModel::defaultServerContainersUpdated, this, [this](const QJsonArray &) { refreshStatus(); });

    m_refreshTimer.setInterval(refreshIntervalMsecs);
    connect(&m_refreshTimer, &QTimer::timeout, this, &SubscriptionStatusController::refreshStatus);
    m_refreshTimer.start();

    QTimer::singleShot(0, this, &SubscriptionStatusController::refreshStatus);
}

bool SubscriptionStatusController::isAvailable() const
{
    return m_isAvailable;
}

bool SubscriptionStatusController::isActive() const
{
    return m_isActive;
}

int SubscriptionStatusController::daysRemaining() const
{
    return m_daysRemaining;
}

QString SubscriptionStatusController::expiresAtText() const
{
    if (!m_expiresAt.isValid()) {
        return QString();
    }
    return m_expiresAt.toLocalTime().toString("yyyy-MM-dd HH:mm:ss");
}

QString SubscriptionStatusController::subscribeUrl() const
{
    return QString(telegramSubscribeUrl);
}

bool SubscriptionStatusController::isExpiringSoon() const
{
    if (!m_isAvailable) {
        return false;
    }
    if (!m_isActive) {
        return true;
    }
    if (!m_expiresAt.isValid()) {
        return false;
    }

    constexpr qint64 oneDaySecs = 24 * 60 * 60;
    return QDateTime::currentDateTime().secsTo(m_expiresAt) <= oneDaySecs;
}

QString SubscriptionStatusController::extractClientPubKey() const
{
    if (!m_serversModel || m_serversModel->getServersCount() <= 0) {
        return {};
    }

    auto defaultContainer = qvariant_cast<DockerContainer>(m_serversModel->getDefaultServerData(QLatin1String("defaultContainer")));
    if (defaultContainer == DockerContainer::None) {
        return {};
    }

    QJsonObject containerConfig = m_settings->containerConfig(m_serversModel->getDefaultServerIndex(), defaultContainer);
    QJsonObject protocolConfig = containerConfig.value(ContainerProps::containerTypeToProtocolString(defaultContainer)).toObject();
    QJsonObject lastConfig = QJsonDocument::fromJson(protocolConfig.value(config_key::last_config).toString().toUtf8()).object();

    return lastConfig.value(config_key::client_pub_key).toString();
}

void SubscriptionStatusController::refreshStatus()
{
    const QString clientPubKey = extractClientPubKey();
    if (clientPubKey.isEmpty()) {
        m_lastRequestedPubKey.clear();
        if (m_isAvailable) {
            m_isAvailable = false;
            emit statusChanged();
        }
        return;
    }

    m_lastRequestedPubKey = clientPubKey;

    QNetworkRequest request { QUrl(QString::fromLatin1(subscriptionStatusUrl)) };
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    request.setTransferTimeout(requestTimeoutMsecs);

    QJsonObject payload;
    payload[QStringLiteral("client_pub_key")] = clientPubKey;

    QNetworkReply *reply = amnApp->networkManager()->post(request, QJsonDocument(payload).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply, clientPubKey]() {
        reply->deleteLater();

        // Drop stale replies belonging to a server that is no longer the default one
        if (clientPubKey != m_lastRequestedPubKey) {
            return;
        }

        if (reply->error() != QNetworkReply::NoError) {
            qDebug() << "SubscriptionStatusController: request failed:" << reply->errorString();
            return;
        }

        QJsonObject response = QJsonDocument::fromJson(reply->readAll()).object();

        m_isActive = response.value(QStringLiteral("active")).toBool();
        m_daysRemaining = response.value(QStringLiteral("days_remaining")).toInt();

        const QString expiresAtString = response.value(QStringLiteral("expires_at")).toString();
        QDateTime expiresAt = QDateTime::fromString(expiresAtString, Qt::ISODateWithMs);
        if (!expiresAt.isValid()) {
            expiresAt = QDateTime::fromString(expiresAtString, Qt::ISODate);
        }
        m_expiresAt = expiresAt;

        m_isAvailable = true;
        emit statusChanged();
    });
}
